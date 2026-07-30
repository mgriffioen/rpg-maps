package com.rpgmaps.tabletop.display

import com.rpgmaps.tabletop.display.protocol.DisplayMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One place the player view can be shown.
 *
 * Two implementations exist and they differ in exactly one interesting way:
 * how the map image gets across.
 *
 *  - [com.rpgmaps.tabletop.display.lan.LanSink] serves the bytes over HTTP and
 *    just sends a URL, because the receiver is on the same Wi-Fi and can fetch.
 *  - [com.rpgmaps.tabletop.display.cast.CastSink] has no such luxury: a
 *    Chromecast receiver cannot reach the tablet, so the image is base64-chunked
 *    through the Cast message channel.
 *
 * Everything else -- viewport, fog, grid -- is small JSON and goes through
 * [send] unchanged.
 */
interface DisplaySink {

    /** Stable identity, used by [DisplayHub] to replace rather than duplicate. */
    val id: String

    val status: StateFlow<SinkStatus>

    /** Messages coming back from the receiver ([ReceiverHello], resync requests). */
    val incoming: SharedFlow<DisplayMessage>

    fun start()

    fun stop()

    /** Fire-and-forget. Implementations must not block the caller. */
    fun send(message: DisplayMessage)

    /**
     * Makes [bytes] the current image. Called once per map change; the sink
     * decides how to deliver it and may re-deliver on reconnect.
     */
    fun presentImage(revision: Int, bytes: ByteArray, mime: String)
}

data class SinkStatus(
    val label: String,
    val connected: Boolean = false,
    /** Human-readable state for the cast bar: an address, a device name, an error. */
    val detail: String = "",
    /** Receiver canvas size in pixels, once it has told us. 0 until then. */
    val receiverW: Int = 0,
    val receiverH: Int = 0,
    /** 0..1 while an image transfer is in flight, or null when idle. */
    val transferProgress: Float? = null,
)
