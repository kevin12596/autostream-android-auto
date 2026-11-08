package com.kcliang.autostream

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Presentation shown on the car VirtualDisplay.
 *
 * Z-order (bottom -> top): WebView, HTML5 fullscreen custom view.
 */
@SuppressLint("SetJavaScriptEnabled")
class CarPresentation(
    outerContext: Context,
    display: Display,
    private val controller: CarDisplayController,
) : Presentation(outerContext, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    var webView: WebView? = null
        private set
    private var container: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var startScript: ScriptHandler? = null
    private var requestedBounds: Rect? = null
    private val layoutScript by lazy { context.assets.open("ix-layout.js").bufferedReader().use { it.readText() } }

    val contentBounds: Rect get() = requestedBounds?.let(::Rect)
        ?: Rect(0, 0, container?.width ?: 0, container?.height ?: 0)

    fun applyContentBounds(bounds: Rect) {
        if (bounds.isEmpty) return
        requestedBounds = Rect(bounds)
        fun params() = FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
            leftMargin = bounds.left; topMargin = bounds.top
        }
        webView?.layoutParams = params()
        customView?.layoutParams = params()
    }

    private fun documentScript(cap: String) = YouTubeJs.hookScript(cap) +
        "\nwindow.__asIxEnabled=${controller.wideLayoutEnabled};\n" + layoutScript

    fun setWideLayout(enabled: Boolean) {
        webView?.evaluateJavascript("if(window.__asIxLayout)window.__asIxLayout.setEnabled($enabled);", null)
        webView?.let { installStartScript(it, WebViewConfig.qualityCap(it.context)) }
    }

    val touchRoot: View? get() = container
    val isCustomViewActive: Boolean get() = customView != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ctx = context
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        val wv = WebView(ctx).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Color.BLACK)
        }
        WebViewConfig.applyCommon(wv)
        installMediaBridge(wv)
        installStartScript(wv, WebViewConfig.qualityCap(ctx))
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                controller.onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view.url != url) return // Ignore a late callback from the previous document.
                // Fallback when document-start scripts are unsupported (hook is idempotent).
                view.evaluateJavascript(documentScript(WebViewConfig.qualityCap(view.context)), null)
                WebViewConfig.saveLastUrl(view.context, url)
                controller.onPageFinished(view, url)
                WebViewConfig.flushCookies()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) controller.onPageLoadFailed(view, request.url.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    controller.onPageLoadFailed(view, request.url.toString())
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                // YouTube is an SPA: most navigations only show up here.
                WebViewConfig.saveLastUrl(view.context, url)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            // HTML5 fullscreen (YouTube fullscreen button). Without this, fullscreen does nothing.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val r = container
                if (customView != null || r == null) {
                    callback.onCustomViewHidden(); return
                }
                controller.cancelTapGesture()
                customView = view
                customViewCallback = callback
                setNativeFullscreen(true)
                view.setBackgroundColor(Color.BLACK)
                r.addView(
                    view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                )
                requestedBounds?.let(::applyContentBounds)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
        root.addView(
            wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        setContentView(root)
        container = root
        webView = wv
        wv.loadUrl(controller.initialUrl())
    }

    /** @return true if HTML5 fullscreen was active and has been exited. */
    fun exitFullscreen(): Boolean {
        val v = customView ?: return false
        controller.cancelTapGesture()
        customView = null
        (v.parent as? ViewGroup)?.removeView(v)
        val cb = customViewCallback
        customViewCallback = null
        try {
            cb?.onCustomViewHidden()
        } catch (t: Throwable) {
            Log.w(TAG, "onCustomViewHidden failed", t)
        }
        setNativeFullscreen(false)
        return true
    }

    private fun setNativeFullscreen(active: Boolean) {
        webView?.evaluateJavascript(
            "window.__asIxNativeFullscreen=$active;if(window.__asIxLayout)window.__asIxLayout.setFullscreen($active);",
            null,
        )
    }

    /** Live quality-cap change: future documents (start script) and the current one. */
    fun applyQualityCap(cap: String) {
        val wv = webView ?: return
        installStartScript(wv, cap)
        wv.evaluateJavascript(YouTubeJs.setQualityCap(cap), null)
    }

    fun destroyWebView() {
        exitFullscreen()
        try {
            startScript?.remove()
        } catch (_: Throwable) {
        }
        startScript = null
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "WebView destroy failed", t)
        }
    }

    private fun installStartScript(wv: WebView, cap: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            startScript?.remove()
        } catch (_: Throwable) {
        }
        startScript = try {
            WebViewCompat.addDocumentStartJavaScript(wv, documentScript(cap), BRIDGE_ORIGINS)
        } catch (t: Throwable) {
            Log.w(TAG, "addDocumentStartJavaScript failed", t)
            null
        }
    }

    private fun installMediaBridge(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Callback runs on the UI thread.
            WebViewCompat.addWebMessageListener(
                wv, BRIDGE_NAME, BRIDGE_ORIGINS,
            ) { _, message, _, _, _ ->
                message.data?.let { controller.onBridgeMessage(it) }
            }
        } else {
            val main = Handler(Looper.getMainLooper())
            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(msg: String?) {
                    if (msg != null && msg.length < 4096) main.post { controller.onBridgeMessage(msg) }
                }
            }, BRIDGE_NAME)
        }
    }

    companion object {
        private const val TAG = "CarPresentation"
        private const val BRIDGE_NAME = "AutoStreamBridge"

        /** Covers the mobile site and YouTube redirects. */
        private val BRIDGE_ORIGINS = setOf(
            "https://*.youtube.com",
            "https://youtube.com",
        )
    }
}
