package com.kcliang.autostream

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.content.Intent
import android.webkit.WebResourceRequest
import android.widget.Toast

/**
 * One-time Google login in a phone WebView that shares the default profile / CookieManager with
 * the car WebView. Uses a Chrome UA without "wv" / "Version/4.0" tokens.
 * The X-Requested-With origin allow-list is deliberately NOT enabled.
 */
class LoginActivity : Activity() {

    private var webView: WebView? = null
    private var videoLinker: PhoneVideoLinker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "登入 Google / YouTube"
        val wv = WebView(this)
        WebViewConfig.applyCommon(wv)
        videoLinker = PhoneVideoLinker(wv) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && videoLinker?.route(request.url.toString(), true) == true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = videoLinker?.route(url, true) == true

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                videoLinker?.route(url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                videoLinker?.pageFinished(url)
                WebViewConfig.flushCookies()
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(this).apply {
            addView(Button(this@LoginActivity).apply {
                text = "返回"
                setOnClickListener { if (wv.canGoBack()) wv.goBack() else finish() }
            })
            addView(Button(this@LoginActivity).apply {
                text = "搜尋／語音輸入"
                setOnClickListener { startActivity(Intent(this@LoginActivity, PhoneSearchActivity::class.java)) }
            })
        })
        root.addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        PhoneInsets.install(this)
        webView = wv
        if (savedInstanceState == null || wv.restoreState(savedInstanceState) == null) {
            wv.loadUrl(WebViewConfig.LOGIN_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        WebViewConfig.flushCookies()
    }

    override fun onResume() {
        super.onResume()
        videoLinker?.route(webView?.url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        videoLinker?.dispose()
        WebViewConfig.flushCookies()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
