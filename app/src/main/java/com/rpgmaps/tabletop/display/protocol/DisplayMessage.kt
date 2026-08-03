// A custom class discriminator is still an experimental part of
// kotlinx.serialization, but it is deliberate here rather than incidental: the
// JavaScript receiver switches on a field named "t", and the default
// "type" would mean maintaining a translation on one side or the other.
@file:OptIn(ExperimentalSerializationApi::class)

package com.rpgmaps.tabletop.display.protocol

import kotlinx.serialization.ExperimentalSerializationApi
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

/** How a fog op paints. Serialises to the lowercase name the receiver reads. */
@Serializable
enum class FogShape {
    @SerialName("brush") BRUSH,
    @SerialName("rect") RECT,
    @SerialName("oval") OVAL,
}

/**
 * One fog edit, in fog-mask pixels.
 *
 * [pts] means different things per [shape], which keeps one op type -- and so
 * one ordering, sequencing and undo path -- for both freehand and shapes:
 *
 *  - [FogShape.BRUSH]: a flat `x0, y0, x1, y1, ...` polyline stroked with
 *    round caps at [radius].
 *  - [FogShape.RECT] and [FogShape.OVAL]: exactly four values, two opposite
 *    corners of the bounding box. [radius] is unused.
 *
 * Edge softness is a blur. For a brush it is `radius * softness`; a shape has
 * no radius, so it is a fraction of the shorter side -- see
 * [SHAPE_SOFTNESS_FACTOR]. Both renderers must use the same formula or the
 * tablet and the TV disagree about where the fog ends.
 */
@Serializable
data class FogOp(
    val reveal: Boolean,
    val radius: Float,
    val softness: Float,
    val pts: List<Float>,
    val shape: FogShape = FogShape.BRUSH,
)

/**
 * Blur radius for a shape edge, as a fraction of its shorter side times the
 * softness slider. Mirrored in `applyFogOp` in receiver/index.html.
 */
const val SHAPE_SOFTNESS_FACTOR: Float = 0.15f

/** Shared by both renderers so a soft edge never collapses to a hard one. */
const val MIN_BLUR_PX: Float = 0.6f

/** Keeps a very soft edge on a very large shape from washing the whole thing out. */
const val MAX_SHAPE_BLUR_PX: Float = 60f

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

/**
 * Quarter-turns clockwise the receiver applies to everything it draws, 0..3.
 *
 * With a TV lying flat on the table there is no natural "up", so which edge
 * the map reads from is a property of where people are sitting. Rotation is a
 * separate message from [ViewportMessage] because it changes once at setup
 * while the viewport changes 25 times a second.
 *
 * It does interact with framing, though: at 90 and 270 degrees the screen axis
 * that [ViewportMessage.halfH] governs is the receiver's *width*. Both
 * renderers work that out from this value rather than being told.
 */
@Serializable
@SerialName("rot")
data class RotationMessage(
    val quarters: Int,
) : DisplayMessage

/**
 * "There." Marks a spot on the players' screen for a couple of seconds.
 *
 * Tagged `mark`, not `ping`, even though the DM's button says Ping: `ping` is
 * already this protocol's WebSocket keepalive (see [Ping]). Two subclasses
 * claiming one discriminator value stops kotlinx.serialization building the
 * serializer for the *whole* hierarchy, and the tablet decodes real keepalives
 * with it -- a heartbeat could have arrived as a marker at (0,0).
 *
 * In map pixels, like the viewport, so it lands in the same place on a screen
 * framed differently -- the DM may be zoomed somewhere else entirely, or the
 * TV frozen.
 *
 * Carries no size. Each renderer works that out from its *own* viewport (see
 * [MARK_RADIUS_FRACTION]) so the marker is equally legible on a 43" TV and a
 * tablet preview, at whatever zoom each happens to be at.
 *
 * Unlike every other message here this one is a **gesture, not state**. It is
 * deliberately left out of the hub's full-state replay: a ping repeated to a
 * receiver that reconnects five minutes later would point at something nobody
 * is talking about any more.
 */
@Serializable
@SerialName("mark")
data class MarkMessage(
    val x: Float,
    val y: Float,
) : DisplayMessage

/**
 * How long a marker lives. The three constants below are mirrored by
 * `drawPings` in receiver/index.html; both renderers animate from the same
 * numbers so the DM sees what the table sees.
 */
const val MARK_DURATION_MS: Long = 2400

/** Expanding rings over that lifetime. */
const val MARK_PULSES: Int = 3

/**
 * Marker radius as a fraction of the visible map height, rather than a fixed
 * number of map pixels. A fixed size would be a speck when zoomed out to the
 * whole dungeon and cover a room when zoomed in.
 */
const val MARK_RADIUS_FRACTION: Float = 0.045f

/** Final fraction of the lifetime spent fading out. */
const val MARK_FADE_TAIL: Float = 0.25f

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

/**
 * Application-level keepalive, receiver -> sender, answered with [Pong].
 *
 * WebSocket has its own ping frames, but a browser cannot send them -- it can
 * only answer them. So a receiver has no way to notice a *half-open* socket:
 * if the tablet drops off the Wi-Fi, the laptop's TCP stack has nothing to
 * send and never learns the peer is gone. No error fires, `onclose` never
 * runs, the page sits there looking connected, and every subsequent reveal is
 * lost until someone reloads by hand.
 *
 * Sending these on a timer and watching for the answer is what turns that into
 * an automatic reconnect.
 *
 * Unrelated to the DM's Ping tool, which is [MarkMessage] on the wire for
 * exactly this reason.
 */
@Serializable
@SerialName("ping")
data object Ping : DisplayMessage

/** Sender -> receiver, in reply to [Ping]. */
@Serializable
@SerialName("pong")
data object Pong : DisplayMessage

/** The shared JSON codec. The receiver's JavaScript reads the same `t` field. */
val DisplayJson: Json = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Custom Cast namespace. Must match `NAMESPACE` in the web receiver. */
const val CAST_NAMESPACE: String = "urn:x-cast:com.rpgmaps.tabletop"

/**
 * Pre-encoded keepalive frames. The transport compares and emits these as
 * plain strings rather than round-tripping through the serializer, because
 * they are sent on a short timer and never vary. Kept next to [Ping] and
 * [Pong] so the two cannot drift apart.
 */
const val PING_JSON: String = """{"t":"ping"}"""
const val PONG_JSON: String = """{"t":"pong"}"""
