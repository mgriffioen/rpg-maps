package com.rpgmaps.tabletop.display

import android.util.Base64
import android.util.Log
import com.rpgmaps.tabletop.display.protocol.BlankMessage
import com.rpgmaps.tabletop.display.protocol.DisplayMessage
import com.rpgmaps.tabletop.display.protocol.FogFillMessage
import com.rpgmaps.tabletop.display.protocol.FogOp
import com.rpgmaps.tabletop.display.protocol.FogOpsMessage
import com.rpgmaps.tabletop.display.protocol.FogResetMessage
import com.rpgmaps.tabletop.display.protocol.GridMessage
import com.rpgmaps.tabletop.display.protocol.MapAnnounced
import com.rpgmaps.tabletop.display.protocol.ResyncRequest
import com.rpgmaps.tabletop.display.protocol.RotationMessage
import com.rpgmaps.tabletop.display.protocol.ViewportMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns "what the players are looking at" and mirrors it to every attached
 * [DisplaySink].
 *
 * The hub keeps a full snapshot of the player-visible state so a receiver that
 * connects late -- or reconnects after the Chromecast drops -- can be brought
 * up to date without the DM touching anything. That replay is the single most
 * important thing this class does; mid-session a Chromecast *will* drop, and
 * recovering silently is the difference between a usable app and a toy.
 *
 * Lives for the life of the process (see
 * [com.rpgmaps.tabletop.RpgMapsApplication]) so casting survives navigating
 * between the library and a map.
 */
class DisplayHub(private val scope: CoroutineScope) {

    private val lock = Mutex()

    private val _sinks = MutableStateFlow<List<DisplaySink>>(emptyList())
    val sinks: StateFlow<List<DisplaySink>> = _sinks.asStateFlow()

    /**
     * Combined status of every sink, for the cast bar. [flatMapLatest] is load
     * bearing: it tears down the previous [combine] when the sink set changes,
     * which a plain nested collect would never get the chance to do.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statuses: StateFlow<List<SinkStatus>> = _sinks
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { it.status }) { it.toList() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // --- current player-visible state ------------------------------------

    /**
     * Whether the players currently have a map in front of them.
     *
     * Exposed so the foreground service knows when it has a reason to exist:
     * without one, Android freezes this process shortly after the DM switches
     * to another app, and the TV stops updating mid-session.
     */
    private val _presenting = MutableStateFlow(false)
    val presenting: StateFlow<Boolean> = _presenting.asStateFlow()

    private var announced: MapAnnounced? = null
    private var imageBytes: ByteArray? = null
    private var imageMime: String = "image/jpeg"
    private var imageRevision: Int = 0

    /**
     * Supplies the full fog mask on demand. Held as a callback rather than a
     * cached PNG because re-encoding the mask on every brush flush would cost
     * more than the drawing itself; it is only needed on (re)connect.
     */
    private var fogSnapshot: (suspend () -> ByteArray?)? = null

    // Touched from the UI thread and read by the pump/push coroutines.
    @Volatile private var fogSeq: Long = 0
    @Volatile private var grid: GridMessage = GridMessage(false, 0f, 0f, 0f)
    @Volatile private var blank: BlankMessage = BlankMessage(false)
    @Volatile private var rotation: RotationMessage = RotationMessage(0)

    /** Latest requested viewport, published on a timer -- see [viewportPump]. */
    @Volatile private var pendingViewport: ViewportMessage? = null
    @Volatile private var sentViewport: ViewportMessage? = null

    private val viewportPump: Job = scope.launch {
        while (isActive) {
            val next = pendingViewport
            if (next != null && next != sentViewport) {
                sentViewport = next
                broadcast(next)
            }
            delay(VIEWPORT_INTERVAL_MS)
        }
    }

    // --- sink management --------------------------------------------------

    fun attach(sink: DisplaySink) {
        scope.launch {
            lock.withLock {
                _sinks.value.firstOrNull { it.id == sink.id }?.let { existing ->
                    if (existing === sink) return@withLock
                    existing.stop()
                    _sinks.value = _sinks.value - existing
                }
                _sinks.value = _sinks.value + sink
            }
            sink.start()

            // Push full state whenever this sink reports a fresh connection,
            // and whenever the receiver explicitly asks to resync.
            scope.launch {
                var wasConnected = false
                sink.status.collect { status ->
                    if (status.connected && !wasConnected) pushFullState(sink)
                    wasConnected = status.connected
                }
            }
            scope.launch {
                sink.incoming.collect { message ->
                    if (message is ResyncRequest) pushFullState(sink)
                }
            }
        }
    }

    fun detach(id: String) {
        scope.launch {
            lock.withLock {
                val sink = _sinks.value.firstOrNull { it.id == id } ?: return@withLock
                _sinks.value = _sinks.value - sink
                sink.stop()
            }
        }
    }

    fun sinkById(id: String): DisplaySink? = _sinks.value.firstOrNull { it.id == id }

    // --- state updates ----------------------------------------------------

    /**
     * Switches the player view to a new map.
     *
     * Callers must set the viewport for the new map *before* calling this --
     * see [setViewport]. This runs asynchronously, so clearing the viewport
     * here would race with, and sometimes discard, the caller's own framing.
     *
     * @param fogSnapshotProvider called on connect/resync to fetch the whole
     *        mask as PNG bytes.
     */
    fun presentMap(
        announcement: MapAnnounced,
        image: ByteArray,
        mime: String = "image/jpeg",
        fogSeq: Long,
        fogSnapshotProvider: suspend () -> ByteArray?,
    ) {
        scope.launch {
            lock.withLock {
                imageRevision++
                announced = announcement.copy(imageRevision = imageRevision)
                imageBytes = image
                imageMime = mime
                this@DisplayHub.fogSeq = fogSeq
                fogSnapshot = fogSnapshotProvider
            }
            _presenting.value = true
            _sinks.value.forEach { pushFullState(it) }
        }
    }

    /** Requests a viewport change. Coalesced and published at [VIEWPORT_INTERVAL_MS]. */
    fun setViewport(viewport: ViewportMessage) {
        pendingViewport = viewport
    }

    fun sendFogOps(seq: Long, ops: List<FogOp>) {
        if (ops.isEmpty()) return
        fogSeq = seq
        broadcast(FogOpsMessage(seq, ops))
    }

    fun sendFogFill(seq: Long, fogged: Boolean) {
        fogSeq = seq
        broadcast(FogFillMessage(seq, fogged))
    }

    /** Replaces the receiver's mask wholesale -- used after undo and redo. */
    fun sendFogSnapshot(seq: Long, png: ByteArray) {
        fogSeq = seq
        broadcast(FogResetMessage(seq, Base64.encodeToString(png, Base64.NO_WRAP)))
    }

    fun setGrid(spec: GridMessage) {
        grid = spec
        broadcast(spec)
    }

    fun setBlank(spec: BlankMessage) {
        blank = spec
        broadcast(spec)
    }

    fun setRotation(spec: RotationMessage) {
        rotation = spec
        broadcast(spec)
    }

    /** Clears the player view back to the idle screen. */
    fun clearMap() {
        scope.launch {
            lock.withLock {
                announced = null
                imageBytes = null
                fogSnapshot = null
                pendingViewport = null
                sentViewport = null
            }
            _presenting.value = false
            broadcast(BlankMessage(true, "No map"))
        }
    }

    // --- plumbing ---------------------------------------------------------

    /**
     * A sink that throws must not take the others down with it, but a throw
     * here is always a bug rather than a routine network hiccup -- sinks queue
     * their I/O and are documented not to block. This once swallowed every fog
     * update on the local network: `send` wrote to the socket synchronously,
     * the DM's brush strokes arrive on the main thread, and Android answers
     * that with NetworkOnMainThreadException. Logged loudly so the next one
     * does not hide for as long.
     */
    private fun broadcast(message: DisplayMessage) {
        _sinks.value.forEach { sink ->
            try {
                sink.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "sink ${sink.id} threw on ${message::class.simpleName}", e)
            }
        }
    }

    /**
     * Brings one sink fully up to date. Ordering matters: the map announcement
     * sizes the receiver's fog mask, so it has to land before any fog data.
     */
    private suspend fun pushFullState(sink: DisplaySink) {
        val announcement: MapAnnounced?
        val bytes: ByteArray?
        val mime: String
        val revision: Int
        val snapshotProvider: (suspend () -> ByteArray?)?
        val seq: Long
        val gridSpec: GridMessage
        val blankSpec: BlankMessage
        val rotationSpec: RotationMessage

        lock.withLock {
            announcement = announced
            bytes = imageBytes
            mime = imageMime
            revision = imageRevision
            snapshotProvider = fogSnapshot
            seq = fogSeq
            gridSpec = grid
            blankSpec = blank
            rotationSpec = rotation
        }

        try {
            if (announcement == null || bytes == null) {
                sink.send(BlankMessage(true, "No map"))
                return
            }

            // Rotation first: it decides which screen axis halfH governs, so
            // sending it after the viewport would frame one frame wrongly.
            sink.send(rotationSpec)
            sink.send(announcement)
            sink.presentImage(revision, bytes, mime)

            val fogPng = snapshotProvider?.invoke()
            if (fogPng != null) {
                sink.send(FogResetMessage(seq, Base64.encodeToString(fogPng, Base64.NO_WRAP)))
            } else {
                sink.send(FogFillMessage(seq, fogged = true))
            }

            sentViewport?.let { sink.send(it) } ?: pendingViewport?.let { sink.send(it) }
            sink.send(gridSpec)
            sink.send(blankSpec)
        } catch (e: Exception) {
            Log.w(TAG, "full-state push to ${sink.id} failed", e)
        }
    }

    fun shutdown() {
        viewportPump.cancel()
        _sinks.value.forEach { it.stop() }
        _sinks.value = emptyList()
    }

    private companion object {
        const val TAG = "DisplayHub"

        /**
         * 40 ms (25 Hz) keeps a pan looking continuous on the TV while staying
         * well inside what a Chromecast's message channel will absorb.
         */
        const val VIEWPORT_INTERVAL_MS = 40L
    }
}
