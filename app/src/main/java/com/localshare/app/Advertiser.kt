package com.localshare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/** Announces this phone on the Wi-Fi so LocalShare TV can list it without scanning or typing. */
class Advertiser(ctx: Context) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(port: Int) {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "LocalShare ${Build.MODEL}"
            serviceType = "_localshare._tcp."
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); listener = l } catch (e: Exception) { }
    }

    @Synchronized
    fun unregister() {
        listener?.let { try { nsd.unregisterService(it) } catch (e: Exception) { } }
        listener = null
    }
}
