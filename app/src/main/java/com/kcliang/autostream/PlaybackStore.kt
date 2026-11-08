package com.kcliang.autostream

import android.content.Context

/** Persists the last watched YouTube video id + position so a cold start can resume it. */
object PlaybackStore {

    data class Saved(val videoId: String, val positionSec: Int)

    private const val KEY_VIDEO = "resume_video_id"
    private const val KEY_POS = "resume_position_sec"

    fun save(context: Context, videoId: String, positionSec: Double) {
        if (!YouTubeJs.isValidVideoId(videoId)) return
        val sec = if (positionSec.isFinite() && positionSec > 0) positionSec.toInt() else 0
        WebViewConfig.prefs(context).edit()
            .putString(KEY_VIDEO, videoId)
            .putInt(KEY_POS, sec)
            .apply()
    }

    fun load(context: Context): Saved? {
        val p = WebViewConfig.prefs(context)
        val id = p.getString(KEY_VIDEO, null)
        if (!YouTubeJs.isValidVideoId(id)) return null
        return Saved(id!!, p.getInt(KEY_POS, 0).coerceAtLeast(0))
    }

    fun clear(context: Context) {
        WebViewConfig.prefs(context).edit().remove(KEY_VIDEO).remove(KEY_POS).apply()
    }
}
