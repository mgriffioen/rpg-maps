package com.rpgmaps.tabletop.display.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Announces the player-view server on the local network over mDNS / DNS-SD, so
 * the TV player app (the `tv/` module) can find the tablet without anyone
 * typing an IP address -- and follows it when the router hands out a new one.
 *
 * Browsers ignore this entirely; typing the address still works as before.
 * Failing to advertise is never fatal: it is logged and the server carries on.
 */
class LanAdvertiser(private val context: Context) {

    /**
     * Looked up on first use, never in the constructor. LanSink -- and so this
     * -- is built in RpgMapsApplication's field initialisers, before Android
     * has attached the application's base context; touching the context then
     * throws a NullPointerException and the app dies on every launch.
     */
    private val nsd: NsdManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager?
        } catch (e: Exception) {
            Log.w(TAG, "no NsdManager", e)
            null
        }
    }
    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(port: Int) {
        val nsd = nsd ?: return
        withdraw()
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising ${info.serviceName} on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "could not advertise on port $port (error $errorCode)")
                if (listener === this) listener = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
        }
    }

    fun withdraw() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(l) }
    }

    companion object {
        private const val TAG = "LanAdvertiser"

        /** Must match `TabletFinder.SERVICE_TYPE` in the TV player app. */
        const val SERVICE_TYPE = "_rpgmaps._tcp"
        const val SERVICE_NAME = "RPG Maps"
    }
}
