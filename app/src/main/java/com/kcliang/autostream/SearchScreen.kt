package com.kcliang.autostream

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.activity.OnBackPressedCallback
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * The host owns the keyboard and speech-to-text UI. A WebView on a VirtualDisplay cannot
 * reliably display an IME on the car screen, so searches use this native template instead.
 */
class SearchScreen(
    carContext: CarContext,
    initialQuery: String = "",
    private val onHome: (() -> Unit)? = null,
    private val onSubmit: ((String) -> Unit)? = null,
) : Screen(carContext) {
    private var searchText = initialQuery
    private var submitted = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoSubmit = Runnable { submit(searchText) }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(autoSubmit) }
            override fun onDestroy(owner: LifecycleOwner) { handler.removeCallbacks(autoSubmit) }
        })
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { returnHome() }
        })
    }

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            if (submitted) return
            this@SearchScreen.searchText = searchText
            handler.removeCallbacks(autoSubmit)
            if (searchText.isNotBlank()) handler.postDelayed(autoSubmit, 1500)
            invalidate()
        }

        override fun onSearchSubmitted(searchText: String) {
            // Some hosts commit the keyboard before sending an empty final callback.
            submit(searchText.ifBlank { this@SearchScreen.searchText })
        }
    }

    override fun onGetTemplate(): Template {
        val query = YouTubeSearch.normalize(searchText)
        val items = ItemList.Builder()
            .setNoItemsMessage("語音或文字輸入後，停止輸入 1.5 秒自動搜尋；也可按搜尋立即送出。")
        if (query.isNotEmpty()) {
            items.addItem(
                Row.Builder()
                    .setTitle("在 YouTube 搜尋「$query」")
                    .setOnClickListener { submit(query) }
                    .build(),
            )
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.APP_ICON)
            .setSearchHint("搜尋 YouTube（停止輸入 1.5 秒自動送出）")
            .setInitialSearchText(searchText)
            .setShowKeyboardByDefault(true)
            .setActionStrip(ActionStrip.Builder().addAction(
                Action.Builder().setTitle("首頁").setOnClickListener { returnHome() }.build(),
            ).addAction(
                Action.Builder().setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_search),
                ).build()).setOnClickListener { submit(searchText) }.build(),
            ).build())
            .setItemList(items.build())
            .build()
    }

    private fun returnHome() {
        if (submitted || lifecycle.currentState == Lifecycle.State.DESTROYED) return
        submitted = true // Reject late host callbacks as well as the pending auto-submit.
        handler.removeCallbacks(autoSubmit)
        onHome?.invoke()
        screenManager.popToRoot()
    }

    private fun submit(text: String) {
        if (submitted || lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val query = YouTubeSearch.normalize(text)
        if (query.isEmpty()) return
        submitted = true
        handler.removeCallbacks(autoSubmit)
        onSubmit?.invoke(query)
        setResult(query)
        finish()
    }
}
