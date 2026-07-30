package com.rpgmaps.tabletop.display.cast

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.rpgmaps.tabletop.display.DisplaySink
import com.rpgmaps.tabletop.display.SinkStatus
import com.rpgmaps.tabletop.display.protocol.CAST_NAMESPACE
import com.rpgmaps.tabletop.display.protocol.DisplayJson
import com.rpgmaps.tabletop.display.protocol.DisplayMessage
import com.rpgmaps.tabletop.display.protocol.ImageChunk
import com.rpgmaps.tabletop.display.protocol.ReceiverHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Streams the player view to a Chromecast.
 *
 * The awkward part is the map image. A Chromecast receiver is a web page on a
 * different device with no route back to the tablet, so unlike the LAN
 * transport it cannot simply fetch a URL. Everything -- including several
 * hundred kilobytes of JPEG -- has to go through Cast's custom message
 * channel, which caps individual messages well under 64 KB.
 *
 * So [presentImage] base64-encodes the JPEG, slices it into [CHUNK_CHARS]
 * pieces and sends them one at a time, waiting for each to be acknowledged
 * before sending the next. Waiting is what keeps a slow first-generation
 * Chromecast from dropping the tail of the transfer; it also gives an honest
 * progress figure for the UI. The transfer is a cancellable [Job] so switching
 * maps mid-send abandons the old one instead of interleaving two images.
 */
class CastSink(
    context: Context,
    private val scope: CoroutineScope,
) : DisplaySink {

    override val id: String = ID

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(SinkStatus(label = "Chromecast", detail = "Not connected"))
    override val status: StateFlow<SinkStatus> = _status.asStateFlow()

    private val _incoming = MutableSharedFlow<DisplayMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<DisplayMessage> = _incoming.asSharedFlow()

    private var castContext: CastContext? = null
    private var session: CastSession? = null
    private var transferJob: Job? = null
    private var writerJob: Job? = null

    /** Ordered outbound queue; see [send]. */
    private val outbound = Channel<String>(Channel.UNLIMITED)

    /** Re-sent automatically when a dropped session comes back. */
    private var lastImage: Triple<Int, ByteArray, String>? = null

    private val messageCallback = Cast.MessageReceivedCallback { _: CastDevice, _: String, message: String ->
        onReceiverMessage(message)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = bind(session, "Connected")
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = bind(session, "Reconnected")

        override fun onSessionEnded(session: CastSession, error: Int) = unbind("Disconnected")
        override fun onSessionSuspended(session: CastSession, reason: Int) = unbind("Suspended")
        override fun onSessionStartFailed(session: CastSession, error: Int) =
            unbind("Could not start (code $error)")

        override fun onSessionResumeFailed(session: CastSession, error: Int) =
            unbind("Could not reconnect (code $error)")

        override fun onSessionStarting(session: CastSession) = setDetail("Connecting...")
        override fun onSessionResuming(session: CastSession, sessionId: String) = setDetail("Reconnecting...")
        override fun onSessionEnding(session: CastSession) = setDetail("Disconnecting...")
    }

    override fun start() {
        scope.launch(Dispatchers.Main) {
            // Null rather than an exception when Google Play services is
            // missing or too old, which is a normal state on some tablets.
            val ctx = awaitCastContext()
            if (ctx == null) {
                _status.value = _status.value.copy(
                    connected = false,
                    detail = "Casting unavailable on this device",
                )
                return@launch
            }

            castContext = ctx
            ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            ctx.sessionManager.currentCastSession?.let { bind(it, "Connected") }
        }
    }

    /**
     * Initialises the Cast framework. Uses the Task API directly rather than
     * `kotlinx-coroutines-play-services` so the app does not carry that
     * dependency for one call.
     */
    private suspend fun awaitCastContext(): CastContext? =
        suspendCancellableCoroutine { continuation ->
            try {
                CastContext.getSharedInstance(appContext, Executor { it.run() })
                    .addOnSuccessListener { context ->
                        if (continuation.isActive) continuation.resume(context)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Cast unavailable", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "Cast initialisation threw", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }

    override fun stop() {
        transferJob?.cancel()
        transferJob = null
        scope.launch(Dispatchers.Main) {
            castContext?.sessionManager
                ?.removeSessionManagerListener(sessionListener, CastSession::class.java)
            unbind("Stopped")
        }
    }

    /**
     * Queued rather than launched per message. Independent coroutines reach
     * the Cast framework in whatever order the dispatcher feels like, and fog
     * strokes applied out of order leave the receiver's mask quietly wrong.
     */
    override fun send(message: DisplayMessage) {
        if (session == null) return
        outbound.trySend(DisplayJson.encodeToString(DisplayMessage.serializer(), message))
    }

    private fun startWriter() {
        writerJob?.cancel()
        writerJob = scope.launch {
            while (outbound.tryReceive().isSuccess) Unit
            for (json in outbound) {
                val current = session ?: continue
                sendRaw(current, json)
            }
        }
    }

    override fun presentImage(revision: Int, bytes: ByteArray, mime: String) {
        lastImage = Triple(revision, bytes, mime)
        startTransfer(revision, bytes, mime)
    }

    // --- session plumbing -------------------------------------------------

    private fun bind(newSession: CastSession, detail: String) {
        session = newSession
        try {
            newSession.setMessageReceivedCallbacks(CAST_NAMESPACE, messageCallback)
        } catch (e: Exception) {
            Log.e(TAG, "could not register namespace $CAST_NAMESPACE", e)
            _status.value = _status.value.copy(
                connected = false,
                detail = "Receiver did not accept the app channel",
            )
            return
        }

        startWriter()
        _status.value = _status.value.copy(
            connected = true,
            detail = newSession.castDevice?.friendlyName ?: detail,
        )
        // DisplayHub replays the full state on the connected edge, but the
        // image is ours to re-send since it does not live in the message log.
        lastImage?.let { (revision, bytes, mime) -> startTransfer(revision, bytes, mime) }
    }

    private fun unbind(detail: String) {
        transferJob?.cancel()
        transferJob = null
        writerJob?.cancel()
        writerJob = null
        runCatching { session?.removeMessageReceivedCallbacks(CAST_NAMESPACE) }
        session = null
        _status.value = _status.value.copy(connected = false, detail = detail, transferProgress = null)
    }

    private fun setDetail(detail: String) {
        _status.value = _status.value.copy(detail = detail)
    }

    private fun onReceiverMessage(message: String) {
        val parsed = try {
            DisplayJson.decodeFromString(DisplayMessage.serializer(), message)
        } catch (e: Exception) {
            Log.d(TAG, "unparseable receiver message: $message", e)
            return
        }
        if (parsed is ReceiverHello) {
            _status.value = _status.value.copy(receiverW = parsed.w, receiverH = parsed.h)
        }
        _incoming.tryEmit(parsed)
    }

    // --- image transfer ---------------------------------------------------

    private fun startTransfer(revision: Int, bytes: ByteArray, mime: String) {
        transferJob?.cancel()
        val session = session ?: return

        transferJob = scope.launch {
            val encoded = withContext(Dispatchers.Default) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            val total = (encoded.length + CHUNK_CHARS - 1) / CHUNK_CHARS
            _status.value = _status.value.copy(transferProgress = 0f)

            for (index in 0 until total) {
                val start = index * CHUNK_CHARS
                val end = minOf(start + CHUNK_CHARS, encoded.length)
                val chunk = ImageChunk(
                    imageRevision = revision,
                    index = index,
                    count = total,
                    mime = mime,
                    data = encoded.substring(start, end),
                )
                val json = DisplayJson.encodeToString(DisplayMessage.serializer(), chunk)

                var sent = false
                for (attempt in 0 until CHUNK_RETRIES) {
                    if (sendRaw(session, json)) {
                        sent = true
                        break
                    }
                    delay(RETRY_DELAY_MS)
                }
                if (!sent) {
                    Log.w(TAG, "image transfer stalled at chunk $index/$total")
                    _status.value = _status.value.copy(
                        transferProgress = null,
                        detail = "Map transfer failed - try reconnecting",
                    )
                    return@launch
                }

                _status.value = _status.value.copy(transferProgress = (index + 1f) / total)
            }

            _status.value = _status.value.copy(transferProgress = null)
        }
    }

    /** Suspends until the Cast framework acknowledges the message. */
    private suspend fun sendRaw(session: CastSession, json: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                session.sendMessage(CAST_NAMESPACE, json)
                    .setResultCallback { status ->
                        if (continuation.isActive) continuation.resume(status.isSuccess)
                    }
            } catch (e: Exception) {
                Log.d(TAG, "sendMessage threw", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }

    companion object {
        const val ID = "cast"
        private const val TAG = "CastSink"

        /**
         * Base64 characters per message. Cast's practical ceiling is 64 KB and
         * the JSON envelope adds a little, so 48 KB leaves comfortable headroom
         * on receivers that are stricter than the documentation.
         */
        private const val CHUNK_CHARS = 48 * 1024

        private const val CHUNK_RETRIES = 3
        private const val RETRY_DELAY_MS = 250L
    }
}
