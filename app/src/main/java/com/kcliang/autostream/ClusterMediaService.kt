package com.kcliang.autostream

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.os.SystemClock

/** Publishes standard audio metadata; BMW decides whether and where to show it. */
class ClusterMediaService : MediaBrowserService() {
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "AutoStream").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { commands?.invoke(true) }
                override fun onPause() { commands?.invoke(false) }
                override fun onStop() { commands?.invoke(false) }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { commands?.invoke(true) }
            })
        }
        sessionToken = session.sessionToken
        active = this
        render()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot("autostream", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val items = mutableListOf<MediaBrowser.MediaItem>()
        if (parentId == "autostream" && title.isNotEmpty()) {
            items.add(MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("current")
                .setTitle(title).setSubtitle(author).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE))
        }
        result.sendResult(items)
    }

    private fun render() {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, author)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position, if (playing) 1f else 0f, SystemClock.elapsedRealtime()).build())
        session.isActive = commands != null && title.isNotEmpty()
    }

    override fun onDestroy() {
        if (active === this) active = null
        session.release()
        super.onDestroy()
    }

    companion object {
        private var active: ClusterMediaService? = null
        private var title = ""
        private var author = ""
        private var playing = false
        private var position = 0L
        private var duration = 0L
        var commands: ((Boolean) -> Unit)? = null
            set(value) { field = value; active?.render() }

        fun update(newTitle: String, newAuthor: String, isPlaying: Boolean, seconds: Double, length: Double) {
            val changed = title != newTitle || author != newAuthor
            title = newTitle.take(300)
            author = newAuthor.take(200)
            playing = isPlaying
            position = if (seconds.isFinite()) (seconds.coerceAtLeast(0.0) * 1000).toLong() else 0
            duration = if (length.isFinite()) (length.coerceAtLeast(0.0) * 1000).toLong() else 0
            active?.render()
            if (changed) active?.notifyChildrenChanged("autostream")
        }
    }
}
