package com.kcliang.autostream

import java.lang.ref.WeakReference

/** Phone and car run in the same process; never retain a car session after it is released. */
object CarSearchBridge {
    private var active = WeakReference<CarDisplayController>(null)

    fun attach(controller: CarDisplayController) {
        active = WeakReference(controller)
    }

    fun detach(controller: CarDisplayController) {
        if (active.get() === controller) active.clear()
    }

    fun search(query: String): Boolean {
        if (YouTubeSearch.normalize(query).isEmpty()) return false
        val controller = active.get() ?: return false
        controller.search(query)
        return true
    }

    fun isConnected(): Boolean = active.get() != null

    fun openVideo(raw: String): Boolean {
        val url = YouTubeNavigation.videoUrl(raw) ?: return false
        val controller = active.get() ?: return false
        controller.openVideo(url)
        return true
    }
}
