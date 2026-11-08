package com.kcliang.autostream

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.CarToast
import org.json.JSONObject

/**
 * Renders a WebView onto the Android Auto map surface:
 *
 *  GL mode (default):  VirtualDisplay -> SurfaceTexture -> [AvDelayRenderer] -> car Surface
 *  Fallback (GL fail): VirtualDisplay -> car Surface
 *
 * The VirtualDisplay / Presentation / WebView are created on the first surface and live until the
 * car Session is destroyed ([release]). When the car surface goes away (user switched to another
 * car app, e.g. Google Maps) only the output is detached, so audio keeps playing; on return the
 * new surface is simply reattached.
 */
class CarDisplayController(private val carContext: CarContext) : SurfaceCallback {
    var wideLayoutEnabled: Boolean = WebViewConfig.prefs(carContext).getBoolean("ix_wide_layout", true)
        private set

    fun setWideLayout(enabled: Boolean) {
        onMain {
            wideLayoutEnabled = enabled
            WebViewConfig.prefs(appContext).edit().putBoolean("ix_wide_layout", enabled).apply()
            scrollAnchorX = null; scrollAnchorY = null
            presentation?.setWideLayout(enabled)
        }
    }

    private val appContext: Context = carContext.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---- Persistent pipeline (survives surface detach) ----
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var renderer: AvDelayRenderer? = null
    private var sinkReader: ImageReader? = null
    private var sinkThread: HandlerThread? = null
    private var displayW = 0
    private var displayH = 0
    private var displayDpi = 0
    private var outputAttached = false
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var latestSurface: SurfaceContainer? = null
    private var screenVisible = true
    private var released = false
    private var presentationRebuilds = 0
    private var pendingUrl: String? = null
    private var scrollAnchorX: Float? = null
    private var scrollAnchorY: Float? = null

    // ---- Page playback state (from the JS bridge) ----
    private var playing = false
    private var curTime = 0.0
    private var videoId: String? = null
    private var videoBounds: VideoTapGesture.Bounds? = null
    private var lastSaveMs = 0L


    // ---- Synthetic drag state (for onScroll) ----
    private var dragActive = false
    private var dragDownTime = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val endDrag = Runnable { finishDrag(MotionEvent.ACTION_UP) }

    private val videoTaps = VideoTapGesture(DOUBLE_TAP_TIMEOUT_MS)
    private var tapGeneration = 0L
    private val confirmSingleTap = Runnable {
        videoTaps.takeSingle()?.let { dispatchPageTap(it.x, it.y) }
    }

    /** Live application of phone/car setting changes. Strong ref: prefs keep listeners weakly. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            WebViewConfig.KEY_AV_DELAY -> renderer?.setDelay(WebViewConfig.avDelayMs(appContext))
            WebViewConfig.KEY_CLUSTER_MEDIA -> configureClusterMedia()
            WebViewConfig.KEY_QUALITY -> presentation?.applyQualityCap(WebViewConfig.qualityCap(appContext))
        }
    }

    private val webView: WebView? get() = presentation?.webView

    /** Root of the car Presentation; covers the full surface, so car coords map 1:1. */
    private val touchTarget: View? get() = presentation?.touchRoot

    init {
        WebViewConfig.prefs(appContext).registerOnSharedPreferenceChangeListener(prefListener)
        CarSearchBridge.attach(this)
        // Retire the legacy opt-in focus owner; WebView owns audio focus exclusively.
        WebViewConfig.prefs(appContext).edit().remove("app_audio_focus").apply()
        configureClusterMedia()
    }

    private fun configureClusterMedia() {
        if (WebViewConfig.clusterMediaEnabled(appContext)) {
            ClusterMediaService.commands = { play -> onMain { if (play) resumeVideo() else pauseVideo() } }
            try {
                appContext.startService(android.content.Intent(appContext, ClusterMediaService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Media service start deferred until host connects", e)
            }
        } else {
            ClusterMediaService.commands = null
            ClusterMediaService.update("", "", false, 0.0, 0.0)
            appContext.stopService(android.content.Intent(appContext, ClusterMediaService::class.java))
        }
    }

    // ------------------------------------------------------------------ SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        Log.i(TAG, "onSurfaceAvailable ${w}x$h dpi=$dpi")
        if (surface == null || w <= 0 || h <= 0) return
        onMain {
            latestSurface = surfaceContainer
            if (screenVisible) attachSurface(surface, w, h, if (dpi > 0) dpi else 160)
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "onVisibleAreaChanged $visibleArea")
        onMain {
            this.visibleArea = Rect(visibleArea)
            refreshViewport()
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "onStableAreaChanged $stableArea")
        onMain { this.stableArea = Rect(stableArea); refreshViewport() }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceDestroyed (detach output only; page keeps playing)")
        onMain {
            latestSurface = null
            savePlayback()
            detachOutput()
        }
    }

    override fun onClick(x: Float, y: Float) {
        onMain {
            if (released || !outputAttached || !x.isFinite() || !y.isFinite()) return@onMain
            val viewport = presentation?.contentBounds ?: return@onMain
            if (!viewport.contains(x.toInt(), y.toInt())) return@onMain
            if (viewport.width() > 0 && viewport.height() > 0) {
                scrollAnchorX = ((x - viewport.left) / viewport.width()).coerceIn(0f, 1f)
                scrollAnchorY = ((y - viewport.top) / viewport.height()).coerceIn(0f, 1f)
            }
            finishDrag(MotionEvent.ACTION_UP)
            main.removeCallbacks(confirmSingleTap)
            val side = videoSideAt(x, y)
            if (side == 0) {
                // Search, links, the video center and native controls keep immediate clicks.
                videoTaps.takeSingle()?.let { dispatchPageTap(it.x, it.y) }
                dispatchPageTap(x, y)
                return@onMain
            }
            val tap = VideoTapGesture.Tap(x, y, SystemClock.uptimeMillis(), side)
            val slop = DOUBLE_TAP_SLOP_DP * (if (displayDpi > 0) displayDpi else 160) / 160f
            val decision = videoTaps.register(tap, slop)
            decision.single?.let { dispatchPageTap(it.x, it.y) }
            decision.double?.let { (first, second) -> seekFromDoubleTap(first, second) }
            if (videoTaps.pending != null) main.postDelayed(confirmSingleTap, DOUBLE_TAP_TIMEOUT_MS)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        onMain {
            if (released || !outputAttached || !distanceX.isFinite() || !distanceY.isFinite()) return@onMain
            cancelTapGesture()
            if (presentation?.isCustomViewActive == false && webView != null) {
                webView?.evaluateJavascript(NestedScrollJs.command(distanceX, distanceY, scrollAnchorX, scrollAnchorY), null)
                return@onMain
            }
            val wv = touchTarget ?: return@onMain
            val now = SystemClock.uptimeMillis()
            if (!dragActive) {
                val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, displayW, displayH)
                dragActive = true
                dragDownTime = now
                dragX = area.exactCenterX()
                dragY = area.exactCenterY()
                dispatch(wv, dragDownTime, now, MotionEvent.ACTION_DOWN, dragX, dragY)
            }
            // GestureDetector convention: distance = previous - current, so the finger moved by -distance.
            dragX = (dragX - distanceX).coerceIn(0f, (wv.width - 1).coerceAtLeast(0).toFloat())
            dragY = (dragY - distanceY).coerceIn(0f, (wv.height - 1).coerceAtLeast(0).toFloat())
            dispatch(wv, dragDownTime, now, MotionEvent.ACTION_MOVE, dragX, dragY)
            main.removeCallbacks(endDrag)
            main.postDelayed(endDrag, DRAG_IDLE_MS)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        onMain {
            if (released || !outputAttached || !velocityX.isFinite() || !velocityY.isFinite()) return@onMain
            cancelTapGesture()
            val wv = webView ?: return@onMain
            if (presentation?.isCustomViewActive == false && !dragActive) {
                val dx = (-velocityX * 0.15f).coerceIn(-displayW * 2f, displayW * 2f)
                val dy = (-velocityY * 0.15f).coerceIn(-displayH * 2f, displayH * 2f)
                wv.evaluateJavascript(NestedScrollJs.command(dx, dy, scrollAnchorX, scrollAnchorY), null)
                return@onMain
            }
            if (dragActive) {
                // Releasing the synthetic drag lets the WebView fling with its own velocity tracking.
                finishDrag(MotionEvent.ACTION_UP)
            } else {
                wv.flingScroll(-velocityX.toInt(), -velocityY.toInt())
            }
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Basic stub: page zoom around the WebView center (focus point ignored).
        onMain {
            cancelTapGesture()
            val wv = webView ?: return@onMain
            val f = scaleFactor.coerceIn(0.5f, 2f)
            if (f != 1f) wv.zoomBy(f)
        }
    }

    // ------------------------------------------------------------------ Controls

    fun setScreenVisible(visible: Boolean) {
        onMain {
            if (released || screenVisible == visible) return@onMain
            screenVisible = visible
            if (!visible) detachOutput() else latestSurface?.let { info ->
                val surface = info.surface
                if (surface != null && surface.isValid) attachSurface(surface, info.width, info.height, if (info.dpi > 0) info.dpi else 160)
            }
        }
    }

    private fun refreshViewport() {
        val area = CarViewport.fit(displayW, displayH, stableArea, visibleArea)
        if (area == null) { detachOutput(); return }
        presentation?.applyContentBounds(area)
        if (screenVisible && !outputAttached && virtualDisplay != null) {
            latestSurface?.surface?.takeIf { it.isValid }?.let(::attachOutput)
        }
    }

    /** Called only after the host's typed or dictated search is submitted. Cancel leaves playback alone. */
    fun search(query: String) {
        val url = YouTubeSearch.url(query) ?: return
        navigate(url)
    }

    fun goHome() { navigate(WebViewConfig.HOME_URL) }

    fun openVideo(raw: String) { YouTubeNavigation.videoUrl(raw)?.let { navigate(it) } }

    private fun navigate(url: String) {
        onMain {
            if (released) return@onMain
            cancelTapGesture()
            finishDrag(MotionEvent.ACTION_CANCEL)
            savePlayback()
            // Keep the request even when a WebView exists: the host can destroy/rebuild its
            // surface while returning from SearchTemplate. Clear only when the results load.
            pendingUrl = url
            presentation?.exitFullscreen()
            val wv = webView
            wv?.loadUrl(url)
        }
    }

    /** @return true if something was closed / the WebView navigated back. */
    fun goBack(): Boolean {
        cancelTapGesture()
        val p = presentation ?: return false
        if (p.exitFullscreen()) return true
        val wv = p.webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack(); true
        } else false
    }

    /** Full teardown: only on car Session destroy. */
    fun release() {
        onMain {
            if (released) return@onMain
            latestSurface = null
            savePlayback()
            teardownPipeline()
            released = true
            CarSearchBridge.detach(this)
            ClusterMediaService.commands = null
            ClusterMediaService.update("", "", false, 0.0, 0.0)
            appContext.stopService(android.content.Intent(appContext, ClusterMediaService::class.java))
            WebViewConfig.prefs(appContext).unregisterOnSharedPreferenceChangeListener(prefListener)
            main.removeCallbacks(endDrag)
            sinkThread?.quitSafely()
            sinkThread = null
        }
    }

    /** A submitted search takes priority across every rebuild until its results are loaded. */
    internal fun initialUrl(): String {
        pendingUrl?.let { return it }
        val lastUrl = WebViewConfig.lastUrl(appContext)
        // A loaded search results page is the current destination, not the old watched video.
        if (lastUrl == WebViewConfig.HOME_URL || YouTubeSearch.queryFromUrl(lastUrl) != null) return lastUrl!!
        val saved = PlaybackStore.load(appContext)
        if (saved != null) {
            return YouTubeJs.watchUrl(saved.videoId, saved.positionSec)
        }
        return lastUrl ?: WebViewConfig.HOME_URL
    }

    internal fun onPageFinished(view: WebView, url: String?) {
        if (released || webView !== view || view.url != url) return
        if (pendingUrl != null && pendingUrl == url) {
            WebViewConfig.saveLastUrl(appContext, url)
            pendingUrl = null
            return
        }
        val pendingQuery = YouTubeSearch.queryFromUrl(pendingUrl) ?: return
        if (YouTubeSearch.queryFromUrl(url) == pendingQuery) {
            WebViewConfig.saveLastUrl(appContext, url)
            pendingUrl = null
        }
    }

    internal fun onPageLoadFailed(view: WebView, url: String?) {
        if (released || webView !== view || pendingUrl == null) return
        if (YouTubeSearch.queryFromUrl(url) == YouTubeSearch.queryFromUrl(pendingUrl)) {
            CarToast.makeText(carContext, "頁面載入失敗，請確認手機網路後再試", CarToast.LENGTH_LONG).show()
        }
    }

    internal fun onPageStarted() {
        scrollAnchorX = null
        scrollAnchorY = null
        cancelTapGesture()
        videoBounds = null
        videoId = null
        curTime = 0.0
        playing = false
    }

    internal fun cancelTapGesture() {
        main.removeCallbacks(confirmSingleTap)
        videoTaps.cancel()
        tapGeneration++
    }

    /** JSON state messages from [YouTubeJs] (UI thread). */
    internal fun onBridgeMessage(raw: String) {
        if (released || !raw.startsWith("{")) return
        val o = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return
        }
        if (o.optString("t") != "state") return
        val wasPlaying = playing
        playing = o.optBoolean("playing", false)
        curTime = o.optDouble("time", 0.0).takeIf { it.isFinite() } ?: 0.0
        val ended = o.optBoolean("ended", false)
        val vid = if (o.isNull("vid")) null else o.optString("vid").takeIf { YouTubeJs.isValidVideoId(it) }
        if (vid != videoId) cancelTapGesture() // includes YouTube's same-document navigation
        videoId = vid
        if (WebViewConfig.clusterMediaEnabled(appContext)) ClusterMediaService.update(
            if (vid != null) o.optString("title", "YouTube") else "",
            o.optString("author", "AutoStream"), playing, curTime, o.optDouble("dur", 0.0),
        )
        videoBounds = o.optJSONArray("rect")?.takeIf { it.length() == 4 }?.let {
            VideoTapGesture.Bounds(
                it.optDouble(0).toFloat(), it.optDouble(1).toFloat(),
                it.optDouble(2).toFloat(), it.optDouble(3).toFloat(),
            )
        }


        if (vid != null) {
            val now = SystemClock.uptimeMillis()
            when {
                ended -> PlaybackStore.clear(appContext)
                o.optString("ev") == "seek" -> {
                    PlaybackStore.save(appContext, vid, curTime); lastSaveMs = now
                }
                playing && now - lastSaveMs >= SAVE_INTERVAL_MS -> {
                    PlaybackStore.save(appContext, vid, curTime); lastSaveMs = now
                }
                wasPlaying && !playing -> {
                    PlaybackStore.save(appContext, vid, curTime); lastSaveMs = now
                }
            }
        }
    }

    // ------------------------------------------------------------------ Pipeline

    private fun attachSurface(surface: Surface, w: Int, h: Int, dpi: Int) {
        if (released) return
        if (outputAttached) detachOutput()
        if (virtualDisplay != null && (w != displayW || h != displayH || dpi != displayDpi)) {
            // A Presentation cannot survive a display-metrics change (it cancels itself), so a
            // geometry change rebuilds everything; playback resumes from the saved position.
            Log.i(TAG, "Surface geometry changed ${displayW}x$displayH@$displayDpi -> ${w}x$h@$dpi; rebuilding")
            savePlayback()
            teardownPipeline()
        }
        if (virtualDisplay == null) buildPipeline(surface, w, h, dpi) else {
            attachOutput(surface)
            // The host may suspend loading while SearchTemplate owns the display. Reapply an
            // unfinished request to the now-visible WebView, rather than relying on hidden loading.
            val view = webView
            if (view != null && pendingUrl != null) main.post {
                val target = pendingUrl
                if (!released && outputAttached && webView === view && target != null && view.url != target) view.loadUrl(target)
            }
        }
        refreshViewport()
    }

    private fun buildPipeline(surface: Surface, w: Int, h: Int, dpi: Int) {
        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return
        displayW = w
        displayH = h
        displayDpi = dpi

        val r = AvDelayRenderer()
        val glOk = try {
            r.start(w, h)
        } catch (t: Throwable) {
            Log.e(TAG, "AvDelayRenderer start threw", t); false
        }
        val input = r.inputSurface
        renderer = if (glOk && input != null) {
            r.setDelay(WebViewConfig.avDelayMs(appContext))
            r
        } else {
            Log.w(TAG, "GL pipeline unavailable -> direct VirtualDisplay output (A/V delay disabled)")
            r.release()
            null
        }

        val vd = try {
            dm.createVirtualDisplay(
                "AutoStream", w, h, dpi, renderer?.inputSurface ?: surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (vd == null) {
            renderer?.release()
            renderer = null
            return
        }
        virtualDisplay = vd
        attachOutput(surface)
        presentationRebuilds = 0
        showPresentation(vd)
    }

    private fun showPresentation(vd: VirtualDisplay) {
        val p = CarPresentation(carContext, vd.display, this)
        p.setOnDismissListener { onPresentationDismissed(p) }
        try {
            p.show()
            presentation = p
            refreshViewport()
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "Presentation.show failed", e)
        }
    }

    /** The system may cancel a Presentation (e.g. display change). Recreate it on the same display. */
    private fun onPresentationDismissed(p: CarPresentation) {
        if (released || presentation !== p) return // our own teardown
        Log.w(TAG, "Presentation dismissed unexpectedly; recreating")
        savePlayback()
        presentation = null
        p.destroyWebView()
        val vd = virtualDisplay ?: return
        if (++presentationRebuilds > 3) {
            Log.e(TAG, "Too many Presentation rebuilds; giving up until next surface")
            return
        }
        main.post { if (!released && presentation == null && virtualDisplay === vd) showPresentation(vd) }
    }

    private fun attachOutput(surface: Surface) {
        val r = renderer
        if (r != null) {
            if (r.attachOutput(surface, displayW, displayH)) {
                outputAttached = true
                return
            }
            Log.e(TAG, "EGL output failed; falling back to direct VirtualDisplay output (A/V delay disabled)")
            virtualDisplay?.surface = surface
            r.release()
            renderer = null
        } else {
            virtualDisplay?.surface = surface
            releaseSink()
        }
        outputAttached = true
    }

    private fun detachOutput() {
        cancelTapGesture()
        if (!outputAttached) return
        outputAttached = false
        finishDrag(MotionEvent.ACTION_CANCEL)
        val r = renderer
        if (r != null) {
            // Keep consuming SurfaceTexture frames, just stop drawing.
            r.detachOutput()
        } else {
            // Direct mode: a null surface turns the VirtualDisplay OFF (could pause rendering /
            // media). Park it on a frame-discarding ImageReader instead.
            virtualDisplay?.surface = ensureSink(displayW, displayH)
        }
    }

    private fun ensureSink(w: Int, h: Int): Surface? {
        sinkReader?.let { return it.surface }
        return try {
            val t = sinkThread ?: HandlerThread("AutoStreamSink").also { it.start(); sinkThread = it }
            val reader = ImageReader.newInstance(w.coerceAtLeast(1), h.coerceAtLeast(1), PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ rd ->
                try {
                    rd.acquireLatestImage()?.close()
                } catch (_: Throwable) {
                }
            }, Handler(t.looper))
            sinkReader = reader
            reader.surface
        } catch (t: Throwable) {
            Log.w(TAG, "sink ImageReader failed", t)
            null
        }
    }

    private fun releaseSink() {
        try {
            sinkReader?.close()
        } catch (_: Throwable) {
        }
        sinkReader = null
    }

    private fun teardownPipeline() {
        cancelTapGesture()
        videoBounds = null
        finishDrag(MotionEvent.ACTION_CANCEL)
        presentation?.let { p ->
            presentation = null // before dismiss: marks the dismissal as ours
            WebViewConfig.saveLastUrl(appContext, p.webView?.url)
            try {
                p.dismiss()
            } catch (t: Throwable) {
                Log.w(TAG, "dismiss failed", t)
            }
            p.destroyWebView()
        }
        virtualDisplay?.release()
        virtualDisplay = null
        renderer?.release()
        renderer = null
        releaseSink()
        outputAttached = false
        playing = false
        WebViewConfig.flushCookies()
    }

    // ------------------------------------------------------------------ Internals

    private fun videoSideAt(x: Float, y: Float): Int {
        if (videoId == null) return 0
        val area = presentation?.contentBounds ?: return 0
        if (area.width() <= 0 || area.height() <= 0) return 0
        val bounds = if (presentation?.isCustomViewActive == true) {
            VideoTapGesture.Bounds(0f, 0f, 1f, 1f)
        } else videoBounds ?: return 0
        return bounds.sideAt((x - area.left) / area.width(), (y - area.top) / area.height())
    }

    private fun seekFromDoubleTap(first: VideoTapGesture.Tap, second: VideoTapGesture.Tap) {
        val wv = webView ?: return
        val area = presentation?.contentBounds ?: return
        if (area.width() <= 0 || area.height() <= 0) return
        val generation = tapGeneration
        val script = YouTubeJs.doubleTapSeek(
            (first.x - area.left) / area.width(), (first.y - area.top) / area.height(),
            (second.x - area.left) / area.width(), (second.y - area.top) / area.height(),
            presentation?.isCustomViewActive == true,
        )
        // The page rechecks live geometry and excludes buttons, ads and non-seekable streams.
        wv.evaluateJavascript(script) { handled ->
            if (generation != tapGeneration || released || !outputAttached || webView !== wv) return@evaluateJavascript
            if (handled != "true") dispatchPageTap(second.x, second.y)
        }
    }

    private fun dispatchPageTap(x: Float, y: Float) {
        if (released || !outputAttached) return
        val target = touchTarget ?: return
        val now = SystemClock.uptimeMillis()
        // Do not fabricate future event times: the host supplies complete click events.
        dispatch(target, now, now, MotionEvent.ACTION_DOWN, x, y)
        dispatch(target, now, now, MotionEvent.ACTION_UP, x, y)
    }

    private fun savePlayback() {
        val vid = videoId ?: return
        PlaybackStore.save(appContext, vid, curTime)
        lastSaveMs = SystemClock.uptimeMillis()
    }

    private fun pauseVideo() {
        webView?.evaluateJavascript(YouTubeJs.PAUSE_ALL, null)
    }

    private fun resumeVideo() {
        webView?.evaluateJavascript(YouTubeJs.PLAY_ALL, null)
    }

    private fun finishDrag(action: Int) {
        main.removeCallbacks(endDrag)
        if (!dragActive) return
        dragActive = false
        val wv = touchTarget ?: return
        dispatch(wv, dragDownTime, SystemClock.uptimeMillis(), action, dragX, dragY)
    }

    private fun dispatch(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            view.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        private const val TAG = "CarDisplay"
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val DOUBLE_TAP_SLOP_DP = 64f
        private const val DRAG_IDLE_MS = 150L
        private const val SAVE_INTERVAL_MS = 5000L
    }
}
