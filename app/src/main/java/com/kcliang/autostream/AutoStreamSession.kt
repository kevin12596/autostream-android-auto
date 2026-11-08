package com.kcliang.autostream

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class AutoStreamSession : Session() {

    private var controller: CarDisplayController? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "Car session destroyed (full teardown)")
                controller?.release()
                controller = null
                PlaybackForegroundService.stop(carContext)
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        controller?.release() // A repeated launch must not leave an older renderer drawing.
        val ctrl = CarDisplayController(carContext)
        controller = ctrl

        carContext.getCarService(AppManager::class.java).setSurfaceCallback(ctrl)
        PlaybackForegroundService.start(carContext)
        return VideoScreen(carContext, ctrl)
    }

    companion object {
        private const val TAG = "AutoStreamSession"
    }
}
