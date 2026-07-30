package com.rpgmaps.tabletop.display.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The wire protocol between the tablet (sender) and whatever is drawing the
 * player view (receiver): a Chromecast running the web receiver, a browser on
 * the local network, or an Android TV WebView.
 *
 * Two rules keep the sender and receiver in sync without either one needing to
 * know the other's screen size:
 *
 *  - **Fog coordinates are fog-mask pixels.** The receiver allocates a mask of
 *    exactly [MapAnnounced.fogW] x [MapAnnounced.fogH] and replays the same
 *    ops, so no normalisation or aspect correction is involved anywhere.
 *  - **The viewport is expressed vertically.** The sender says "centre the view
 *    at (cx, cy) and show `halfH` map pixels above and below it". The receiver
 *    derives the horizontal extent from its own aspect ratio. That means a 16:9
 *    TV and a 4:3 tablet preview always agree on the vertical framing and each
 *    shows as much width as it has.
 *
 * Anything the receiver sends back uses the same envelope; see [ReceiverHello].
 */
@Serializable
@JsonClassDiscriminator("t")
sealed interface DisplayMessage

/**
 * Announces which map is on screen. Always sent before the image data, so the
 * receiver can size its fog mask before any ops arrive.
 *
 * [imageRevision] bumps whenever the image bytes change (a different map, or
 * the same map re-rendered at a new resolution). Receivers ignore image chunks
 * whose revision is not the current one, which makes a mid-transfer map switch
 * safe.
 */
@Serializable
@SerialName("map")
data class MapAnnounced(
    val mapId: String,
    val name: String,
    val imageW: Int,
    val imageH: Int,
    val fogW: Int,
    val fogH: Int,
    val imageRevision: Int,
) : DisplayMessage

/** The image is fetchable over HTTP. Used by the LAN transport. */
@Serializable
@SerialName("imgurl")
data class ImageUrl(
    val imageRevision: Int,
    val url: String,
) : DisplayMessage

/**
 * One slice of a base64 image. Used by the Cast transport, which has no way to
 * fetch bytes from the tablet and must push everything through the message
 * channel. See `CastSink.CHUNK_CHARS` for why the slices are small.
 */
@Serializable
@SerialName("img")
data class ImageChunk(
    val imageRevision: Int,
    val index: Int,
    val count: Int,
    val mime: String,
    val data: String,
) : DisplayMessage

/** Centre and vertical half-extent of the visible region, in map pixels. */
@Serializable
@SerialName("view")
data class ViewportMessage(
    val cx: Float,
    val cy: Float,
    val halfH: Float,
) : DisplayMessage

/** A single brush stroke. [pts] is a flat `x0, y0, x1, y1, ...` run in fog pixels. */
@Serializable
data class FogOp(
    val reveal: Boolean,
    val radius: Float,
    val softness: Float,
    val pts: List<Float>,
)

/**
 * Incremental fog changes. [seq] is the mask revision *after* these ops are
 * applied; a receiver that sees a gap asks for a full resync rather than
 * drifting silently.
 */
@Serializable
@SerialName("fog")
data class FogOpsMessage(
    val seq: Long,
    val ops: List<FogOp>,
) : DisplayMessage

/** Replaces the whole mask with a base64 PNG. Sent on connect and after undo. */
@Serializable
@SerialName("fogset")
data class FogResetMessage(
    val seq: Long,
    val png: String,
) : DisplayMessage

/** Fills the mask without transferring a PNG -- "reveal all" and "hide all". */
@Serializable
@SerialName("fogfill")
data class FogFillMessage(
    val seq: Long,
    val fogged: Boolean,
) : DisplayMessage

/** Square grid overlay drawn on the player view, in map pixels. */
@Serializable
@SerialName("grid")
data class GridMessage(
    val enabled: Boolean,
    val pxPerSquare: Float,
    val offsetX: Float,
    val offsetY: Float,
    val colorArgb: String = "#59FFFFFF",
) : DisplayMessage

/** Hides the map behind a curtain without losing any state. */
@Serializable
@SerialName("blank")
data class BlankMessage(
    val on: Boolean,
    val text: String = "",
) : DisplayMessage

/**
 * Receiver -> sender. Reports the receiver's canvas size in device pixels and
 * its physical size when it knows it, which is what lets the sender compute a
 * viewport that renders one grid square at a real-world inch on the TV.
 */
@Serializable
@SerialName("hello")
data class ReceiverHello(
    val w: Int,
    val h: Int,
    val label: String = "",
) : DisplayMessage

/** Receiver -> sender. "I missed something, send me the full state." */
@Serializable
@SerialName("resync")
data class ResyncRequest(
    val haveSeq: Long = -1,
) : DisplayMessage

/** The shared JSON codec. The receiver's JavaScript reads the same `t` field. */
val DisplayJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Custom Cast namespace. Must match `NAMESPACE` in the web receiver. */
const val CAST_NAMESPACE: String = "urn:x-cast:com.rpgmaps.tabletop"
