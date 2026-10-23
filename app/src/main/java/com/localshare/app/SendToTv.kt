package com.localshare.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Scan the code shown by LocalShare TV and hand the TV this phone's address and password. */
object SendToTv {
    private val PRIVATE = Regex("""^(10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)$""")

    fun scan(ctx: Context, st: UiState, done: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { send(it.rawValue.orEmpty(), st, done) }
            .addOnFailureListener { done("Could not open the scanner. Your TV will also list this phone by itself.") }
    }

    private fun send(code: String, st: UiState, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val uri = try { Uri.parse(code) } catch (e: Exception) { null }
        val host = uri?.host.orEmpty()
        // The code carries a private address only; never send a password to anything outside the home network.
        if (uri == null || uri.scheme != "http" || uri.path != "/pair" || !PRIVATE.matches(host) || uri.port !in 1024..65535) {
            return done("That is not a LocalShare TV code")
        }
        val mine = st.urls.firstOrNull { it.substringAfter("//").substringBeforeLast('.') == host.substringBeforeLast('.') }
            ?: return done("This phone is not on the same Wi-Fi as the TV")
        Thread {
            val msg = try {
                val c = URL(code).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 4000
                c.readTimeout = 6000
                c.doOutput = true
                val body = "name=${URLEncoder.encode(Build.MODEL, "UTF-8")}&url=${URLEncoder.encode(mine, "UTF-8")}&password=${URLEncoder.encode(st.password, "UTF-8")}"
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode == 200) "Sent! Look at your TV." else "The TV said no. Show a fresh code on the TV and try again."
            } catch (e: Exception) {
                "Could not reach the TV. Check that both are on the same Wi-Fi."
            }
            main.post { done(msg) }
        }.start()
    }
}
