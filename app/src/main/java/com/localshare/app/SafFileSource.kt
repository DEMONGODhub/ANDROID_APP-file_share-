package com.localshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.localshare.app.server.FileSource
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a folder the user picked with the system folder picker (Storage Access Framework).
 * Works on every Android version without any "all files access" permission, and seeks
 * efficiently inside big videos.
 */
class SafFileSource(context: Context, private val tree: Uri) : FileSource {
    private class Node(val docId: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    private val ctx = context.applicationContext
    private val cr = ctx.contentResolver
    private val cache = ConcurrentHashMap<String, Node>() // "a/b/c" -> node
    private val root = Node(DocumentsContract.getTreeDocumentId(tree), "", true, 0, 0)

    private fun key(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun children(parent: Node, parentKey: String): List<Node>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Node>()
        val cursor = try { cr.query(uri, cols, null, null, null) } catch (e: Exception) { null }
        if (cursor == null) return null
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                val n = Node(
                    it.getString(0), name,
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (it.isNull(3)) 0 else it.getLong(3),
                    if (it.isNull(4)) 0 else it.getLong(4),
                )
                out.add(n)
                cache[key(parentKey, name)] = n
            }
        }
        if (cache.size > 30000) cache.clear()
        return out
    }

    private fun resolve(path: List<String>): Node? {
        var node = root
        var k = ""
        for (seg in path) {
            val parentKey = k
            k = key(parentKey, seg)
            val hit = cache[k]
            if (hit != null) { node = hit; continue }
            if (!node.dir) return null
            children(node, parentKey) ?: return null
            node = cache[k] ?: return null
        }
        return node
    }

    override fun list(path: List<String>): List<FileSource.Entry>? {
        val node = resolve(path) ?: return null
        if (!node.dir) return null
        val kids = children(node, path.joinToString("/")) ?: return null
        return kids.map { FileSource.Entry(it.name, it.dir, it.size, it.mtime) }
    }

    override fun stat(path: List<String>): FileSource.Entry? {
        val n = resolve(path) ?: return null
        return FileSource.Entry(n.name, n.dir, n.size, n.mtime)
    }

    override fun open(path: List<String>, offset: Long): InputStream {
        val node = resolve(path) ?: throw IOException("not found")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, node.docId)
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("cannot open")
        val s = ParcelFileDescriptor.AutoCloseInputStream(pfd) as FileInputStream
        if (offset > 0) {
            try {
                s.channel.position(offset)
            } catch (e: IOException) { // non-seekable provider: fall back to skipping
                var left = offset
                while (left > 0) {
                    val n = s.skip(left)
                    if (n <= 0) break
                    left -= n
                }
            }
        }
        return s
    }

    private fun docUri(node: Node) = DocumentsContract.buildDocumentUriUsingTree(tree, node.docId)

    override fun create(dir: List<String>, name: String): OutputStream {
        val parent = resolve(dir)?.takeIf { it.dir } ?: throw IOException("folder not found")
        val k = key(dir.joinToString("/"), name)
        if (cache.containsKey(k) || children(parent, dir.joinToString("/"))?.any { it.name == name } == true) {
            throw IOException("name taken")
        }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val made = DocumentsContract.createDocument(cr, docUri(parent), mime, name) ?: throw IOException("cannot create")
        cache.remove(k)
        return cr.openOutputStream(made, "w") ?: throw IOException("cannot write")
    }

    override fun delete(path: List<String>): Boolean {
        val node = resolve(path) ?: return false
        if (node.dir) return false
        val ok = try { DocumentsContract.deleteDocument(cr, docUri(node)) } catch (e: Exception) { false }
        if (ok) cache.remove(path.joinToString("/"))
        return ok
    }

    override fun thumbnail(path: List<String>, maxPx: Int): ByteArray? {
        val node = resolve(path)?.takeIf { !it.dir } ?: return null
        val uri = docUri(node)
        val ext = node.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val bmp = providerThumb(uri, maxPx) ?: when {
            ext in IMAGE_EXT -> decodeImage(uri, maxPx)
            ext in VIDEO_EXT -> videoFrame(uri, maxPx)
            ext == "pdf" -> pdfPage(uri, maxPx)
            else -> null
        } ?: return null
        val out = ByteArrayOutputStream(32 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun providerThumb(uri: Uri, maxPx: Int): Bitmap? = try {
        DocumentsContract.getDocumentThumbnail(cr, uri, Point(maxPx, maxPx), null)
    } catch (e: Exception) { null }

    private fun decodeImage(uri: Uri, maxPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx && o.outHeight / (sample * 2) >= maxPx) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }?.let { fit(it, maxPx) }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun videoFrame(uri: Uri, maxPx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { fit(it, maxPx) }
        } catch (e: Exception) { null } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    private fun pdfPage(uri: Uri, maxPx: Int): Bitmap? = try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { pr ->
                if (pr.pageCount == 0) null else pr.openPage(0).use { page ->
                    val w = maxPx
                    val h = (maxPx.toFloat() * page.height / page.width).toInt().coerceIn(1, maxPx * 2)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun fit(b: Bitmap, maxPx: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxPx) return b
        val f = maxPx.toFloat() / m
        val s = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
        if (s !== b) b.recycle()
        return s
    }

    private companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")
    }
}
