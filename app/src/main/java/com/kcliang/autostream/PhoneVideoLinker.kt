package com.kcliang.autostream

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** A connected phone acts as a remote: intercept video links before a second player starts. */
class PhoneVideoLinker(private val web: WebView, private val status: (String) -> Unit) {
    private var lastSynced: String? = null
    private var startScript: ScriptHandler? = null
    private var hasBridge = false

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "AutoStreamPhone", ORIGINS) { _, message, _, mainFrame, _ ->
                if (mainFrame) {
                    val url = YouTubeNavigation.videoUrl(message.data)
                    if (url != null && !route(url, true)) web.loadUrl(url)
                }
            }
            hasBridge = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                startScript = WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, ORIGINS)
            }
        }
    }

    fun pageFinished(url: String?) {
        if (hasBridge) web.evaluateJavascript(SCRIPT, null)
        route(url)
    }

    /** True means the car received it; standalone phone browsing otherwise proceeds normally. */
    fun route(raw: String?, explicitClick: Boolean = false): Boolean {
        val url = YouTubeNavigation.videoUrl(raw) ?: return false
        if (!CarSearchBridge.isConnected()) return false
        // Also cover SPA/history navigation, where shouldOverrideUrlLoading is not called.
        web.evaluateJavascript(YouTubeJs.PAUSE_ALL, null)
        if (explicitClick || lastSynced != url) {
            if (!CarSearchBridge.openVideo(url)) return false
            lastSynced = url
            status("已將影片傳送到車機；手機保留作選片遙控器")
        }
        return true
    }

    fun dispose() {
        startScript?.remove()
        if (hasBridge) WebViewCompat.removeWebMessageListener(web, "AutoStreamPhone")
    }

    companion object {
        private val ORIGINS = setOf("https://*.youtube.com", "https://youtube.com")
        private val SCRIPT = """
            (function(){
              if (window.top !== window || window.__asPhoneLinks || !window.AutoStreamPhone) return;
              window.__asPhoneLinks = true;
              document.addEventListener('click', function(e){
                var node = e.target && (e.target.nodeType === 3 ? e.target.parentElement : e.target);
                var a = node && node.closest && node.closest('a[href]');
                if (!a || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
                try {
                  var u = new URL(a.href, location.href), id = null;
                  if (u.protocol !== 'https:' || u.username || u.password || u.port) return;
                  if (['m.youtube.com','www.youtube.com','youtube.com'].indexOf(u.hostname) >= 0) {
                    id = u.pathname === '/watch' ? u.searchParams.get('v') :
                         (u.pathname.indexOf('/shorts/') === 0 ? u.pathname.slice(8) : null);
                  } else if (u.hostname === 'youtu.be') id = u.pathname.slice(1);
                  if (!id || !/^[A-Za-z0-9_-]{6,20}$/.test(id)) return;
                  window.AutoStreamPhone.postMessage(u.href);
                  e.preventDefault(); e.stopImmediatePropagation();
                } catch(ignore) {}
              }, true);
            })();
        """.trimIndent()
    }
}
