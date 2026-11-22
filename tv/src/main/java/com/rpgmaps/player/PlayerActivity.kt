package com.rpgmaps.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The whole TV app: the tablet's player view, full screen, kept alive.
 *
 * What it adds over opening the address in a TV browser:
 *  - no browser chrome at all, and no screensaver while it is open;
 *  - it finds the tablet by itself ([TabletFinder]), so nobody types an IP
 *    address with a remote, and it follows the tablet to a new address;
 *  - it notices when the tablet goes away and reloads when it comes back, on
 *    top of the page's own WebSocket reconnect.
 *
 * Remote: Menu or Back opens the panel; Back again returns to the map.
 *
 * All state lives on the main thread. Network probes run on [io] and post
 * their results back.
 */
class PlayerActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var webHolder: FrameLayout
    private lateinit var panel: View
    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var address: EditText
    private lateinit var resume: Button

    private lateinit var finder: TabletFinder
    private var web: WebView? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** `host:port` of the tablet the page is loaded from, or null. */
    private var current: String? = null

    /** The last address mDNS reported. Usually the same as [current]. */
    private var lastFound: String? = null

    /** True once the page has loaded without a main-frame error. */
    private var pageOk = false
    private var loadError = false
    private var loadStartedAt = 0L

    /** Consecutive failed health checks of [current]. */
    private var failures = 0

    private var resumed = false
    private val tickRunnable = Runnable { tick() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        webHolder = findViewById(R.id.webHolder)
        panel = findViewById(R.id.panel)
        status = findViewById(R.id.status)
        notice = findViewById(R.id.notice)
        address = findViewById(R.id.address)
        resume = findViewById(R.id.resume)

        address.setText(savedAddress() ?: "")
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectManually()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.connect).setOnClickListener { connectManually() }
        findViewById<Button>(R.id.search).setOnClickListener { searchAgain() }
        findViewById<Button>(R.id.exit).setOnClickListener { finish() }
        resume.setOnClickListener { hidePanel() }

        finder = TabletFinder(this) { found -> onTabletFound(found) }

        createWebView()
        showPanel(if (savedAddress() != null) "Connecting to the tablet…" else "Looking for the tablet…")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        goFullScreen()
        acquireWifiLock()
        web?.onResume()
        finder.start()
        scheduleTick(0)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tickRunnable)
        finder.stop()
        releaseWifiLock()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        destroyWebView()
        io.shutdownNow()
        super.onDestroy()
    }

    /* ----------------------------- remote ----------------------------- */

    /**
     * Back and Menu both open the panel, so a stray press during a session
     * never drops the map. From the panel, Back returns to the map, or leaves
     * the app when there is no map to return to.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    panel.visibility != View.VISIBLE -> showPanel(statusLine())
                    pageOk -> hidePanel()
                    code == KeyEvent.KEYCODE_BACK -> finish()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /* ------------------------------ panel ----------------------------- */

    private fun showPanel(message: String) {
        status.text = message
        resume.visibility = if (pageOk) View.VISIBLE else View.GONE
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            (if (pageOk) resume else address).requestFocus()
        }
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        goFullScreen()
    }

    private fun statusLine(): String {
        val cur = current
        return when {
            cur == null -> "Looking for the tablet…"
            pageOk && failures == 0 -> "Showing the map from $cur"
            else -> "Can't reach the tablet at $cur. Retrying…"
        }
    }

    private fun connectManually() {
        val target = normalize(address.text.toString())
        if (target == null) {
            status.text = "Type the address shown in the tablet's Display settings."
            return
        }
        status.text = "Connecting to $target…"
        io.execute {
            val ok = probe(target)
            main.post {
                if (isFinishing) return@post
                if (ok) {
                    load(target)
                } else {
                    status.text = "Nothing answered at $target. Is RPG Maps open on the tablet, on the same Wi-Fi?"
                }
            }
        }
    }

    private fun searchAgain() {
        status.text = "Looking for the tablet…"
        finder.restart()
        scheduleTick(0)
    }

    /* --------------------------- connection --------------------------- */

    private fun onTabletFound(found: String) {
        lastFound = found
        val cur = current
        // A different address while the current one is healthy is most likely
        // a second tablet; keep showing the one we have. If the current one is
        // struggling, this is probably the same tablet on a new IP.
        if (cur == null || (found != cur && (!pageOk || failures > 0))) {
            scheduleTick(0)
        }
    }

    private fun scheduleTick(delayMs: Long) {
        if (!resumed) return
        main.removeCallbacks(tickRunnable)
        main.postDelayed(tickRunnable, delayMs)
    }

    /**
     * One round of "is everything still alright?". Checks the tablet we are
     * showing, or looks for one to show, then schedules the next round.
     */
    private fun tick() {
        val was = current
        val found = lastFound
        val saved = savedAddress()
        io.execute {
            val reached: String? = if (was != null) {
                when {
                    probe(was) -> was
                    found != null && found != was && probe(found) -> found
                    else -> null
                }
            } else {
                listOfNotNull(found, saved).distinct().firstOrNull { probe(it) }
            }
            main.post { onTickResult(was, reached) }
        }
    }

    private fun onTickResult(was: String?, reached: String?) {
        if (isFinishing || !resumed) return
        if (current != was) {
            // The user connected somewhere else while this round ran.
            scheduleTick(HEALTHY_INTERVAL_MS)
            return
        }

        if (reached != null) {
            failures = 0
            notice.visibility = View.GONE
            val stuckLoading = !pageOk && SystemClock.elapsedRealtime() - loadStartedAt > LOAD_TIMEOUT_MS
            if (reached != current || loadError || stuckLoading) {
                load(reached)
            }
            if (panel.visibility == View.VISIBLE && !pageOk) status.text = statusLine()
            scheduleTick(HEALTHY_INTERVAL_MS)
            return
        }

        if (was != null) {
            failures++
            if (failures >= FAILURES_BEFORE_NOTICE) {
                notice.text = "Lost the tablet at $was. Reconnecting…"
                notice.visibility = View.VISIBLE
            }
            if (!pageOk) showPanel(statusLine())
            if (failures % FAILURES_BEFORE_NEW_SEARCH == 0) finder.restart()
        } else if (panel.visibility == View.VISIBLE && savedAddress() != null && lastFound == null) {
            status.text = "Looking for the tablet… (last seen at ${savedAddress()})"
        }
        scheduleTick(RETRY_INTERVAL_MS)
    }

    private fun load(target: String) {
        val view = web ?: createWebView()
        current = target
        failures = 0
        pageOk = false
        loadError = false
        loadStartedAt = SystemClock.elapsedRealtime()
        notice.visibility = View.GONE
        saveAddress(target)
        address.setText(target)
        view.loadUrl("http://$target/")
    }

    private fun onPageLoaded() {
        if (loadError) return
        pageOk = true
        failures = 0
        hidePanel()
    }

    private fun onPageFailed() {
        loadError = true
        pageOk = false
        showPanel("Can't reach the tablet at ${current ?: "?"}. Retrying…")
        scheduleTick(RETRY_INTERVAL_MS)
    }

    /* ----------------------------- webview ---------------------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        // The page takes no input. Keeping the WebView out of focus means the
        // remote's buttons come to this activity instead.
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadError = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view === web && url != null && url.startsWith("http")) onPageLoaded()
            }

            // The modern overload calls this one for main-frame errors on
            // every API level, and this one is all API 22 has.
            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (view === web) onPageFailed()
            }

            /**
             * Low-memory TV sticks do kill the renderer. Without handling it,
             * the whole app would be killed with it; instead, start over.
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) {
                    destroyWebView()
                    val target = current
                    createWebView()
                    if (target != null) load(target)
                }
                return true
            }
        }

        webHolder.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        web = view
        return view
    }

    private fun destroyWebView() {
        val view = web ?: return
        web = null
        pageOk = false
        webHolder.removeView(view)
        runCatching { view.destroy() }
    }

    /* ------------------------------ system ---------------------------- */

    /** Fire TV has no system bars, but Android TV and Google TV boxes can. */
    private fun goFullScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    /** Stops Wi-Fi power saving from parking the radio between reveals. */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager? ?: return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = try {
            wifi.createWifiLock(mode, "rpgmaps-player").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    /* ----------------------------- helpers ---------------------------- */

    private fun savedAddress(): String? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null)

    private fun saveAddress(value: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ADDRESS, value).apply()
    }

    companion object {
        private const val PREFS = "player"
        private const val KEY_ADDRESS = "address"

        /** The tablet app's default port; see AppSettings.DEFAULT_PORT. */
        const val DEFAULT_PORT = 8770

        private const val HEALTHY_INTERVAL_MS = 10_000L
        private const val RETRY_INTERVAL_MS = 4_000L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 2_500

        /** About twelve seconds of silence before the notice appears. */
        private const val FAILURES_BEFORE_NOTICE = 3

        /** About half a minute of failures, then a fresh mDNS search. */
        private const val FAILURES_BEFORE_NEW_SEARCH = 8

        /**
         * Turns whatever was typed into `host:port`: tolerates a leading
         * `http://`, a trailing slash or path, and a missing port.
         */
        fun normalize(raw: String): String? {
            var s = raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .trim()
            if (s.isEmpty() || s.contains(' ')) return null
            val hasPort = if (s.startsWith("[")) s.contains("]:") else s.contains(':')
            if (!hasPort) s = "$s:$DEFAULT_PORT"
            return s
        }

        /** The tablet's `/health` route answers "OK" whenever its server is up. */
        fun probe(target: String): Boolean = try {
            val connection = URL("http://$target/health").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.useCaches = false
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
