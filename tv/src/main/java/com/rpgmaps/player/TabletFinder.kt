package com.rpgmaps.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Finds the tablet on the local network over mDNS / DNS-SD.
 *
 * The tablet app advertises its player-view server as [SERVICE_TYPE] (see
 * `LanAdvertiser` in the tablet app). Every time it is seen, [onFound] gets its
 * address as `host:port`, on the main thread -- including again after the
 * tablet restarts its server or the router gives it a new IP.
 *
 * NsdManager can only resolve one service at a time on older Android (a
 * second call fails with FAILURE_ALREADY_ACTIVE), so resolves are queued.
 */
class TabletFinder(
    context: Context,
    private val onFound: (String) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager?
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    private val main = Handler(Looper.getMainLooper())

    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Bumped on every stop, so callbacks from an earlier search are dropped. */
    private var generation = 0

    fun start() {
        val nsd = nsd ?: return
        if (discovery != null) return

        // Some TV Wi-Fi drivers drop multicast entirely without this.
        multicastLock = try {
            wifi?.createMulticastLock("rpgmaps-player")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "no multicast lock", e)
            null
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start ($errorCode)")
                main.post { if (discovery === this) discovery = null }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post { enqueue(serviceInfo) }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            discovery = null
        }
    }

    fun stop() {
        generation++
        pending.clear()
        resolving = false
        discovery?.let { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        discovery = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /**
     * A fresh search. Some mDNS stacks only report a service once per search,
     * so this is how a tablet that came back on a new address gets noticed.
     */
    fun restart() {
        stop()
        start()
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (discovery == null) return
        pending.addLast(info)
        resolveNext()
    }

    private fun resolveNext() {
        val nsd = nsd ?: return
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        val gen = generation

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (gen != generation) return@post
                    resolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        main.postDelayed({ if (gen == generation) enqueue(info) }, RETRY_MS)
                    }
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = addressOf(serviceInfo)
                main.post {
                    if (gen != generation) return@post
                    resolving = false
                    if (address != null) onFound(address)
                    resolveNext()
                }
            }
        }

        try {
            // Deprecated on API 34 in favour of registerServiceInfoCallback,
            // which does not exist on the Fire OS versions this has to run on.
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        } catch (e: Exception) {
            Log.w(TAG, "resolveService threw", e)
            resolving = false
            main.post { resolveNext() }
        }
    }

    @Suppress("DEPRECATION")
    private fun addressOf(info: NsdServiceInfo): String? {
        val hosts: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // IPv4 first: an IPv6 link-local address needs a scope id that a URL
        // cannot carry, so it is only ever a last resort.
        val host = hosts.firstOrNull { it is Inet4Address } ?: hosts.firstOrNull() ?: return null
        val text = host.hostAddress ?: return null
        val hostPart = if (host is Inet6Address) "[${text.substringBefore('%')}]" else text
        return "$hostPart:${info.port}"
    }

    companion object {
        private const val TAG = "TabletFinder"

        /** Must match `LanAdvertiser.SERVICE_TYPE` in the tablet app. */
        const val SERVICE_TYPE = "_rpgmaps._tcp"

        private const val RETRY_MS = 500L
    }
}
