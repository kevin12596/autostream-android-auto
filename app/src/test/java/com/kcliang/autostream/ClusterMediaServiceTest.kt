package com.kcliang.autostream

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.util.ReflectionHelpers
import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ClusterMediaServiceTest.RecordingSession::class])
class ClusterMediaServiceTest {
    // Robolectric does not connect MediaController to MediaSession; capture actual OS-bound payloads.
    @Implements(MediaSession::class)
    class RecordingSession : ShadowMediaSession() {
        var recordedMetadata: MediaMetadata? = null
        var recordedState: PlaybackState? = null
        @Implementation protected fun setMetadata(value: MediaMetadata?) { recordedMetadata = value }
        @Implementation protected fun setPlaybackState(value: PlaybackState?) { recordedState = value }
    }

    @Test fun publishesTitleAuthorAndPlaybackPositionThenClearsOnDisconnect() {
        val serviceController = Robolectric.buildService(ClusterMediaService::class.java).create()
        val service = serviceController.get()
        try {
            ClusterMediaService.commands = { }
            ClusterMediaService.update("測試歌曲", "測試頻道", true, 12.5, 180.0)
            val session: MediaSession = ReflectionHelpers.getField(service, "session")
            val media = Shadow.extract<RecordingSession>(session)
            assertEquals("測試歌曲", requireNotNull(media.recordedMetadata).getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals("測試頻道", requireNotNull(media.recordedMetadata).getString(MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals(PlaybackState.STATE_PLAYING, requireNotNull(media.recordedState).state)
            assertEquals(12500L, requireNotNull(media.recordedState).position)
            ClusterMediaService.update("測試歌曲", "測試頻道", false, 13.0, 180.0)
            assertEquals(PlaybackState.STATE_PAUSED, requireNotNull(media.recordedState).state)
            ClusterMediaService.commands = null
            ClusterMediaService.update("", "", false, 0.0, 0.0)
            assertEquals("", requireNotNull(media.recordedMetadata).getString(MediaMetadata.METADATA_KEY_TITLE))
        } finally {
            ClusterMediaService.commands = null
            serviceController.destroy()
        }
    }
}
