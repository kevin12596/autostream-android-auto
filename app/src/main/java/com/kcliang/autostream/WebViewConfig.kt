package com.kcliang.autostream

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Shared WebView configuration for the phone login WebView and the car-screen WebView.
 * Both run in the same process and therefore share the default WebView profile and the
 * singleton [CookieManager], so a one-time Google login on the phone is reused on the car.
 */
object WebViewConfig {
    const val HOME_URL = "https://m.youtube.com/"
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https://m.youtube.com"

    private const val PREFS = "autostream"
    private const val LEGACY_DESKTOP_UA = "desktop_ua"
    private const val KEY_LAST_URL = "last_url"
    const val KEY_CLUSTER_MEDIA = "cluster_media_enabled"
    private const val FALLBACK_CHROME_MAJOR = "130"

    /** Quality cap: [YouTubeJs.QUALITY_MAX] (default) / hd1080 / hd720 / auto. */
    const val KEY_QUALITY = "quality_cap"

    /** A/V sync: video delay in ms (0 = passthrough). */
    const val KEY_AV_DELAY = "av_delay_ms"
    const val AV_DELAY_MAX_MS = 800
    const val AV_DELAY_STEP_MS = 50

    fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun qualityCap(context: Context): String =
        YouTubeJs.sanitizeCap(prefs(context).getString(KEY_QUALITY, YouTubeJs.QUALITY_MAX))

    fun setQualityCap(context: Context, cap: String) {
        prefs(context).edit().putString(KEY_QUALITY, YouTubeJs.sanitizeCap(cap)).apply()
    }

    fun avDelayMs(context: Context): Int = normalizeDelay(prefs(context).getInt(KEY_AV_DELAY, 0))

    fun setAvDelayMs(context: Context, ms: Int) {
        prefs(context).edit().putInt(KEY_AV_DELAY, normalizeDelay(ms)).apply()
    }

    fun normalizeDelay(ms: Int): Int {
        val c = ms.coerceIn(0, AV_DELAY_MAX_MS)
        return ((c + AV_DELAY_STEP_MS / 2) / AV_DELAY_STEP_MS) * AV_DELAY_STEP_MS
    }

    /**
     * When false (default) the WebView/Chromium manages audio focus itself. Requesting focus from
     * the app as well makes the two holders steal focus from each other, which pauses <video>
     * ~100 ms after it starts.
     */
    fun clusterMediaEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CLUSTER_MEDIA, false)

    fun setClusterMediaEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLUSTER_MEDIA, enabled).apply()
    }

    fun lastUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_URL, null)?.let { YouTubeJs.mobileUrl(it) }

    fun saveLastUrl(context: Context, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return
        prefs(context).edit().putString(KEY_LAST_URL, YouTubeJs.mobileUrl(url)).apply()
    }

    private fun defaultUa(context: Context): String =
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (t: Throwable) {
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$FALLBACK_CHROME_MAJOR.0.0.0 Mobile Safari/537.36"
        }

    /** Mobile Chrome UA: the system WebView UA without the "; wv" and "Version/4.0" tokens. */
    fun mobileUserAgent(context: Context): String =
        defaultUa(context)
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+(\\.\\d+)*\\s*"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    @SuppressLint("SetJavaScriptEnabled")
    fun applyCommon(webView: WebView) {
        // Upgrades must ignore and remove the old desktop setting without clearing login cookies.
        val preferences = prefs(webView.context)
        if (preferences.contains(LEGACY_DESKTOP_UA)) {
            preferences.edit().remove(LEGACY_DESKTOP_UA).apply()
        }
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.userAgentString = mobileUserAgent(webView.context)
        // NOTE: intentionally NOT calling WebSettingsCompat.setRequestedWithHeaderOriginAllowList.

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
    }
}
