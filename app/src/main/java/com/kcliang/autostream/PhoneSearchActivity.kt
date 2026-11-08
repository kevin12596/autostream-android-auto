package com.kcliang.autostream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Real phone display: its normal keyboard works, and cookies stay shared with the car. */
class PhoneSearchActivity : Activity() {
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var loadFailed = false
    private var videoLinker: PhoneVideoLinker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "YouTube 搜尋"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        query = EditText(this).apply {
            id = R.id.phone_search_query
            hint = "影片、歌曲或頻道"
            contentDescription = "搜尋關鍵字"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString(STATE_QUERY).orEmpty())
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { searchPhone(); true } else false
            }
        }
        root.addView(query)
        root.addView(button("切換手機輸入法", android.view.View.generateViewId()) {
            query.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        })
        root.addView(LinearLayout(this).apply {
            addView(button("在手機搜尋", R.id.phone_search_submit, ::searchPhone),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("語音輸入", R.id.phone_search_voice, ::recognizeSpeech),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(button("傳送搜尋到車機", R.id.phone_search_car) {
            if (searchText() == null) return@button
            hideKeyboard()
            status.text = if (CarSearchBridge.search(query.text.toString())) {
                "已傳送搜尋到車機；請切回車機 AutoStream 查看結果"
            } else {
                "尚未連上 AutoStream 車機版，請先在車機開啟 AutoStream"
            }
        })
        status = TextView(this).apply {
            id = R.id.phone_search_status
            text = "連線時點影片會傳到車機；未連線時在手機播放"
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        web = WebView(this).apply { id = R.id.phone_search_web }
        WebViewConfig.applyCommon(web)
        videoLinker = PhoneVideoLinker(web) { status.text = it }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && videoLinker?.route(request.url.toString(), true) == true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = videoLinker?.route(url, true) == true

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                videoLinker?.route(url)
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                videoLinker?.pageFinished(url)
                WebViewConfig.flushCookies()
                if (!loadFailed && YouTubeSearch.queryFromUrl(url) != null) status.text = "搜尋結果"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    loadFailed = true
                    status.text = "頁面載入失敗，請確認網路後再按搜尋"
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    loadFailed = true
                    status.text = "頁面載入失敗（${response.statusCode}），請稍後再試"
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        PhoneInsets.install(this)
        val savedWeb = savedInstanceState?.getBundle(STATE_WEB)
        if (savedWeb == null || web.restoreState(savedWeb) == null) web.loadUrl(WebViewConfig.HOME_URL)
    }

    private fun searchText(): String? {
        val text = YouTubeSearch.normalize(query.text.toString())
        if (text.isEmpty()) { query.error = "請先輸入搜尋關鍵字"; return null }
        query.error = null
        return text
    }

    private fun searchPhone() {
        val text = searchText() ?: return
        hideKeyboard()
        status.text = "正在搜尋…"
        web.loadUrl(YouTubeSearch.url(text)!!)
    }

    private fun recognizeSpeech() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "說出想搜尋的影片、歌曲或頻道")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(speech, REQUEST_SPEECH)
        } catch (_: ActivityNotFoundException) {
            status.text = "手機沒有可用的語音輸入服務，請改用鍵盤輸入"
        } catch (_: SecurityException) {
            status.text = "無法開啟語音輸入服務，請改用鍵盤輸入"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { it.isNotBlank() } ?: return
        query.setText(text)
        query.setSelection(query.length())
        status.text = "已填入語音關鍵字，請選擇手機搜尋或傳送到車機"
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun button(label: String, viewId: Int, click: () -> Unit) = Button(this).apply {
        id = viewId
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, query.text.toString())
        outState.putBundle(STATE_WEB, Bundle().also { web.saveState(it) })
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        WebViewConfig.flushCookies()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        videoLinker?.route(web.url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        videoLinker?.dispose()
        WebViewConfig.flushCookies()
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val REQUEST_SPEECH = 42
        private const val STATE_QUERY = "search_query"
        private const val STATE_WEB = "search_web"
    }
}
