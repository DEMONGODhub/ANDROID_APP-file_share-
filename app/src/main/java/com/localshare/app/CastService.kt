package com.localshare.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream

/** Shares the phone's screen with devices that were let in: every frame becomes a JPEG the web page shows. */
class CastService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCast(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java) else intent?.getParcelableExtra("data")
        val hub = ShareController.streamHub()
        if (data == null || code != Activity.RESULT_OK || hub == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, 2, notification(), type)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            projection = p
            val t = HandlerThread("cast").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopCast() } }, h)
            val m = resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(m.widthPixels, m.heightPixels))
            val w = (m.widthPixels * scale).toInt() and 1.inv()
            val hh = (m.heightPixels * scale).toInt() and 1.inv()
            val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
            reader = r
            hub.start(24000, 2)
            r.setOnImageAvailableListener({ rd ->
                val img = try { rd.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastAt < 100) return@setOnImageAvailableListener // about 10 frames a second is plenty
                    lastAt = now
                    val pl = img.planes[0]
                    val rowPad = pl.rowStride - pl.pixelStride * w
                    val bm = Bitmap.createBitmap(w + rowPad / pl.pixelStride, hh, Bitmap.Config.ARGB_8888)
                    bm.copyPixelsFromBuffer(pl.buffer)
                    val out = ByteArrayOutputStream(64 * 1024)
                    (if (rowPad > 0) Bitmap.createBitmap(bm, 0, 0, w, hh) else bm).compress(Bitmap.CompressFormat.JPEG, 55, out)
                    bm.recycle()
                    val jpg = out.toByteArray()
                    lastJpeg = jpg
                    hub.pushFrame(jpg)
                } catch (e: Exception) {
                } finally { img.close() }
            }, h)
            display = p.createVirtualDisplay("localshare-cast", w, hh, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
            // a still screen produces no new frames, so repeat the last one for anyone who just opened the page
            h.postDelayed(object : Runnable {
                override fun run() {
                    val j = lastJpeg
                    if (active && j != null && System.currentTimeMillis() - lastAt > 900) ShareController.streamHub()?.pushFrame(j)
                    if (active) h.postDelayed(this, 1000)
                }
            }, 1000)
            active = true
            ShareController.setOnStopStream { stopCast() }
            ShareController.refresh()
        } catch (e: Exception) {
            stopCast()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun stopCast() {
        active = false
        val d = display; val rd = reader; val p = projection; val t = thread
        display = null; reader = null; projection = null; thread = null; handler = null // cleared first: p.stop() calls back into here
        try { ShareController.streamHub()?.stop() } catch (e: Exception) { }
        try { d?.release() } catch (e: Exception) { }
        try { rd?.close() } catch (e: Exception) { }
        try { p?.stop() } catch (e: Exception) { }
        t?.quitSafely()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        ShareController.refresh()
    }

    override fun onDestroy() {
        if (active) stopCast()
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = PendingIntent.getService(this, 2, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_folder)
            .setContentTitle("Sharing your screen")
            .setContentText("People you let in can watch it. Tap Stop to end.")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.localshare.app.CAST_STOP"
        const val CHANNEL = "cast"
        @Volatile var active = false

        fun start(ctx: Context, code: Int, data: Intent) {
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java).putExtra("code", code).putExtra("data", data))
        }

        fun stop(ctx: Context) {
            if (active) ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}
