package com.localshare.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.localshare.app.server.FileSource
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a folder the user picked with the system folder picker (Storage Access Framework).
 * Works on every Android version without any "all files access" permission, and seeks
 * efficiently inside big videos.
 */
class SafFileSource(ctx: Context, private val tree: Uri) : FileSource {
    private class Node(val docId: String, val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    private val cr = ctx.applicationContext.contentResolver
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
}
