package com.rpgmaps.tabletop.display.lan

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny web server that hands out the player view and streams state to it.
 *
 * Routes:
 * ```
 * GET /              the receiver page (assets/receiver/index.html)
 * GET /image?rev=N   the current map render as JPEG
 * GET /health        plain-text OK, handy when a TV browser shows nothing
 * (websocket upgrade on any path)  the live state channel
 * ```
 *
 * The same HTML is used by the Chromecast receiver, so there is exactly one
 * implementation of the player view to keep correct.
 */
class LanServer(
    private val context: Context,
    port: Int,
    private val onClientCountChanged: (Int) -> Unit,
    private val onClientMessage: (String) -> Unit,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArrayList<StateSocket>()

    @Volatile
    private var image: ByteArray? = null

    @Volatile
    private var imageMime: String = "image/jpeg"

    @Volatile
    private var imageRevision: Int = 0

    val clientCount: Int get() = clients.size

    fun setImage(revision: Int, bytes: ByteArray, mime: String) {
        image = bytes
        imageMime = mime
        imageRevision = revision
    }

    /** Sends one JSON message to every connected player view. */
    fun broadcast(json: String) {
        for (client in clients) {
            try {
                client.send(json)
            } catch (e: IOException) {
                Log.d(TAG, "dropping dead client", e)
                clients.remove(client)
                onClientCountChanged(clients.size)
            }
        }
    }

    /** Keeps NAT table entries and idle sockets alive between combat rounds. */
    fun pingAll() {
        for (client in clients) {
            try {
                client.ping(PING_PAYLOAD)
            } catch (e: IOException) {
                clients.remove(client)
                onClientCountChanged(clients.size)
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = StateSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> serveReceiverPage()
        "/image" -> serveImage()
        "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        else -> newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "Not found. Open / for the player view.",
        )
    }

    private fun serveReceiverPage(): Response = try {
        val html = context.assets.open(RECEIVER_ASSET).use { it.readBytes() }
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            ByteArrayInputStream(html),
            html.size.toLong(),
        ).apply {
            // The page is tiny and changes with every app build.
            addHeader("Cache-Control", "no-store")
        }
    } catch (e: IOException) {
        Log.e(TAG, "receiver asset missing", e)
        newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            "text/plain",
            "Player view asset is missing from the app.",
        )
    }

    private fun serveImage(): Response {
        val bytes = image ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "No map is being displayed.",
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            imageMime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).apply {
            // Revision is in the query string, so a hit is always the right image.
            addHeader("Cache-Control", "public, max-age=86400, immutable")
            addHeader("X-Map-Revision", imageRevision.toString())
        }
    }

    inner class StateSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        override fun onOpen() {
            clients.add(this)
            onClientCountChanged(clients.size)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
            onClientCountChanged(clients.size)
        }

        override fun onMessage(message: WebSocketFrame) {
            onClientMessage(message.textPayload)
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.d(TAG, "websocket error", exception)
            clients.remove(this)
            onClientCountChanged(clients.size)
        }
    }

    override fun stop() {
        for (client in clients) {
            runCatching { client.close(WebSocketFrame.CloseCode.GoingAway, "server stopping", false) }
        }
        clients.clear()
        super.stop()
    }

    companion object {
        private const val TAG = "LanServer"
        private const val RECEIVER_ASSET = "receiver/index.html"
        private val PING_PAYLOAD = byteArrayOf(0x9)

        /**
         * No socket read timeout. NanoHTTPD's 5 s default would tear down an
         * idle player view between encounters, which is exactly when nobody is
         * looking and nobody would notice until the map stopped updating.
         */
        const val NO_READ_TIMEOUT = 0
    }
}
