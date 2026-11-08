package com.kcliang.autostream

import org.junit.Assert.*
import org.junit.Test

class VideoTapGestureTest {
    private fun tap(x: Float, time: Long, side: Int = -1) = VideoTapGesture.Tap(x, 100f, time, side)

    @Test fun singleTapWaitsForConfirmationAndIsDeliveredOnce() {
        val g = VideoTapGesture(300)
        val first = tap(100f, 1000)
        assertEquals(VideoTapGesture.Decision(), g.register(first, 64f))
        assertEquals(first, g.takeSingle())
        assertNull(g.takeSingle())
    }

    @Test fun doubleTapConsumesBothTapsWithoutTogglingPlayback() {
        val g = VideoTapGesture(300)
        val first = tap(100f, 1000)
        val second = tap(120f, 1200)
        g.register(first, 64f)
        assertEquals(VideoTapGesture.Decision(double = first to second), g.register(second, 64f))
        assertNull(g.takeSingle())
    }

    @Test fun rightDoubleTapAndSuccessivePairsAreSupported() {
        val g = VideoTapGesture(300)
        for (start in listOf(1000L, 1400L)) {
            g.register(tap(800f, start, 1), 64f)
            assertNotNull(g.register(tap(810f, start + 100, 1), 64f).double)
        }
    }

    @Test fun lateTapConfirmsFirstAndStartsNewSequence() {
        val g = VideoTapGesture(300)
        val first = tap(100f, 1000)
        val second = tap(100f, 1301)
        g.register(first, 64f)
        assertEquals(VideoTapGesture.Decision(single = first), g.register(second, 64f))
        assertEquals(second, g.takeSingle())
    }

    @Test fun distantOrOppositeSideTapsAreNotASeek() {
        for (second in listOf(tap(200f, 1100), tap(100f, 1100, 1))) {
            val g = VideoTapGesture(300)
            val first = tap(100f, 1000)
            g.register(first, 64f)
            assertEquals(VideoTapGesture.Decision(single = first), g.register(second, 64f))
        }
    }

    @Test fun cancelOnNavigationOrDragDiscardsPendingTap() {
        val g = VideoTapGesture(300)
        g.register(tap(100f, 1000), 64f)
        g.cancel()
        assertNull(g.takeSingle())
        assertNull(g.register(tap(100f, 1100), 64f).double)
    }

    @Test fun videoCenterAndNativeControlsKeepTheirNormalClicks() {
        val b = VideoTapGesture.Bounds(0f, 0f, 1f, 1f)
        assertEquals(-1, b.sideAt(0.2f, 0.5f))
        assertEquals(1, b.sideAt(0.8f, 0.5f))
        for ((x, y) in listOf(0.5f to 0.5f, 0.2f to 0.1f, 0.8f to 0.9f, 0.99f to 0.5f)) {
            assertEquals(0, b.sideAt(x, y))
        }
    }

    @Test fun sideIsRelativeToInlineVideoRatherThanWholeCarScreen() {
        val b = VideoTapGesture.Bounds(0.1f, 0.2f, 0.5f, 0.6f)
        assertEquals(1, b.sideAt(0.43f, 0.4f))
        assertEquals(0, b.sideAt(0.8f, 0.4f))
    }

    @Test fun invalidVideoBoundsNeverCaptureClicks() {
        assertEquals(0, VideoTapGesture.Bounds(0f, 0f, 0f, 0f).sideAt(0.2f, 0.5f))
        assertEquals(0, VideoTapGesture.Bounds(Float.NaN, 0f, 1f, 1f).sideAt(0.2f, 0.5f))
    }
}
