package com.rpgmaps.tabletop

import android.app.Application
import com.rpgmaps.tabletop.data.AppSettings
import com.rpgmaps.tabletop.data.MapRepository
import com.rpgmaps.tabletop.display.DisplayHub
import com.rpgmaps.tabletop.display.cast.CastSink
import com.rpgmaps.tabletop.display.lan.LanSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the few things that must outlive any one screen: the library, the
 * settings, and the display hub with its sinks.
 *
 * Casting in particular has to survive navigation -- walking back to the
 * library to pick the next map should not drop the Chromecast connection and
 * make the players watch the app reconnect.
 *
 * Dependencies are handed out from here rather than through a DI framework.
 * At this size that is less machinery to learn and less indirection to read.
 */
class RpgMapsApplication : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(this) }
    val repository: MapRepository by lazy { MapRepository(this) }
    val displayHub: DisplayHub by lazy { DisplayHub(appScope) }

    /**
     * The current LAN sink. Replaced (not mutated) when the port setting
     * changes; [DisplayHub.attach] swaps it in by id. Most UI reads the
     * address out of the hub's status rather than touching this directly.
     */
    @Volatile
    var lanSink: LanSink = LanSink(this, appScope, AppSettings.DEFAULT_PORT)
        private set

    val castSink: CastSink by lazy { CastSink(this, appScope) }

    override fun onCreate() {
        super.onCreate()

        displayHub.attach(castSink)
        displayHub.attach(lanSink)

        // Re-bind the server if the DM picks a different port in settings.
        appScope.launch {
            settings.flow
                .map { it.serverPort }
                .distinctUntilChanged()
                .drop(1) // the initial value is already running
                .collect { port ->
                    val replacement = LanSink(this@RpgMapsApplication, appScope, port)
                    lanSink = replacement
                    displayHub.attach(replacement)
                }
        }
    }
}
