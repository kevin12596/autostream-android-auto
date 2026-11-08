package com.kcliang.autostream

import android.graphics.Rect

/** The intersection is in Surface pixels, never in physical panel inches or CSS pixels. */
object CarViewport {
    fun fit(width: Int, height: Int, stable: Rect?, visible: Rect?): Rect? {
        if (width <= 0 || height <= 0) return null
        val area = Rect(0, 0, width, height)
        for (limit in listOf(stable, visible)) {
            if (limit != null && (limit.isEmpty || !area.intersect(limit))) return null
        }
        return area
    }
}
