package com.kcliang.autostream

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class VideoScreen(
    carContext: CarContext,
    private val controller: CarDisplayController,
) : Screen(carContext) {
    private var lastSearchText = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { controller.setScreenVisible(true) }
            override fun onPause(owner: LifecycleOwner) { controller.setScreenVisible(false) }
            override fun onStop(owner: LifecycleOwner) { controller.setScreenVisible(false) }
        })
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.goBack()) {
                    // Nothing to go back to in the WebView: let the host handle it (exits app).
                    isEnabled = false
                    carContext.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        // Action.PAN lets the user enter pan mode, in which the host forwards scroll/fling gestures.
        val mapActionStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        // A single search entry replaces the non-interactive app icon. Input is rendered by
        // Android Auto itself rather than trying to show a keyboard inside the VirtualDisplay.
        val searchStrip = ActionStrip.Builder().addAction(
            Action.Builder().setTitle("返回").setOnClickListener {
                if (!controller.goBack()) carContext.finishCarApp()
            }.build(),
        ).addAction(
            Action.Builder()
                .setTitle("搜尋")
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search)).build())
                .setOnClickListener { openSearch() }
                .build(),
        ).addAction(
            Action.Builder().setTitle(if (controller.wideLayoutEnabled) "原生頁面" else "雙欄播放")
                .setOnClickListener {
                    controller.setWideLayout(!controller.wideLayoutEnabled)
                    invalidate()
                }.build(),
        ).build()
        return NavigationTemplate.Builder()
            .setActionStrip(searchStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { /* pan mode state not needed; gestures arrive via SurfaceCallback */ }
            .build()
    }

    private fun openSearch() {
        if (screenManager.top !== this) return
        controller.cancelTapGesture()
        screenManager.push(SearchScreen(carContext, lastSearchText, onHome = { controller.goHome() }) { query ->
            lastSearchText = query
            controller.search(query)
        })
    }
}
