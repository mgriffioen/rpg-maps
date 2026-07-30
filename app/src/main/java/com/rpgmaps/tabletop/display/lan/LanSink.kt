package com.rpgmaps.tabletop.display.lan

import android.content.Context
import android.util.Log
import com.rpgmaps.tabletop.display.DisplaySink
import com.rpgmaps.tabletop.display.SinkStatus
import com.rpgmaps.tabletop.display.protocol.DisplayJson
import com.rpgmaps.tabletop.display.protocol.DisplayMessage
import com.rpgmaps.tabletop.display.protocol.ImageUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Shows the player view on anything on the same Wi-Fi that can open a URL: a
 * laptop plugged into the TV, an Android TV browser or WebView, a second
 * tablet, or your own phone to check what the players can see.
 *
 * This transport needs no accounts, no developer console and no internet, so it
 * is also the fastest way to confirm the app works before dealing with Cast.
 */
class LanSink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferredPort: Int,
) : DisplaySink {

    override val id: String = ID

    private val _status = MutableStateFlow(SinkStatus(label = "Local network", detail = "Stopped"))
    override val status: StateFlow<SinkStatus> = _status.asStateFlow()

    private val _incoming = MutableSharedFlow<DisplayMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<DisplayMessage> = _incoming.asSharedFlow()

    private var server: LanServer? = null
    private var pingJob: Job? = null

    /** `http://192.168.1.20:8770`, or null while stopped. Shown to the DM. */
    var url: String? = null
        private set

    override fun start() {
        if (server != null) return

        var lastError: IOException? = null
        for (offset in 0 until PORT_ATTEMPTS) {
            val port = preferredPort + offset
            val candidate = LanServer(
                context = context,
                port = port,
                onClientCountChanged = ::onClientCountChanged,
                onClientMessage = ::onClientMessage,
            )
            try {
                candidate.start(LanServer.NO_READ_TIMEOUT, false)
                server = candidate
                val host = NetworkUtils.primaryAddress()
                url = host?.let { "http://$it:$port" }
                _status.value = SinkStatus(
                    label = "Local network",
                    connected = false,
                    detail = url ?: "Not on a network",
                )
                startPinging()
                return
            } catch (e: IOException) {
                lastError = e
                runCatching { candidate.stop() }
            }
        }

        Log.e(TAG, "could not bind a port starting at $preferredPort", lastError)
        _status.value = SinkStatus(
            label = "Local network",
            connected = false,
            detail = "Could not open port $preferredPort",
        )
    }

    override fun stop() {
        pingJob?.cancel()
        pingJob = null
        runCatching { server?.stop() }
        server = null
        url = null
        _status.value = SinkStatus(label = "Local network", detail = "Stopped")
    }

    override fun send(message: DisplayMessage) {
        val server = server ?: return
        server.broadcast(DisplayJson.encodeToString(DisplayMessage.serializer(), message))
    }

    override fun presentImage(revision: Int, bytes: ByteArray, mime: String) {
        val server = server ?: return
        server.setImage(revision, bytes, mime)
        // Relative so the receiver resolves it against whatever address it used
        // to reach us -- which may not be the one we would have guessed.
        send(ImageUrl(revision, "/image?rev=$revision"))
    }

    private fun onClientCountChanged(count: Int) {
        val base = url ?: "Not on a network"
        _status.value = _status.value.copy(
            connected = count > 0,
            detail = if (count > 0) "$count connected - $base" else base,
        )
    }

    private fun onClientMessage(text: String) {
        val message = try {
            DisplayJson.decodeFromString(DisplayMessage.serializer(), text)
        } catch (e: Exception) {
            Log.d(TAG, "unparseable message from player view: $text", e)
            return
        }
        if (message is com.rpgmaps.tabletop.display.protocol.ReceiverHello) {
            _status.value = _status.value.copy(
                receiverW = message.w,
                receiverH = message.h,
            )
        }
        _incoming.tryEmit(message)
    }

    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                runCatching { server?.pingAll() }
            }
        }
    }

    companion object {
        const val ID = "lan"
        private const val TAG = "LanSink"

        /** Try a few ports before giving up; another app may hold the first. */
        private const val PORT_ATTEMPTS = 8
        private const val PING_INTERVAL_MS = 20_000L
    }
}
