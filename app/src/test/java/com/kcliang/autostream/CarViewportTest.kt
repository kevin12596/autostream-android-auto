package com.kcliang.autostream

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarViewportTest {
    @Test fun intersectsHostAreasWithoutChangingTheirCoordinates() {
        val stable = Rect(80, 32, 1200, 600)
        val visible = Rect(0, 0, 1100, 580)
        assertEquals(Rect(80, 32, 1100, 580), CarViewport.fit(1280, 720, stable, visible))
        assertEquals(Rect(80, 32, 1200, 600), stable)
        assertEquals(Rect(0, 0, 1100, 580), visible)
    }

    @Test fun occludedOrInvalidAreasDoNotFallBackToDrawingOverOtherApps() {
        assertNull(CarViewport.fit(1280, 720, null, Rect()))
        assertNull(CarViewport.fit(1280, 720, Rect(0, 0, 100, 100), Rect(200, 200, 300, 300)))
        assertNull(CarViewport.fit(0, 720, null, null))
        assertEquals(Rect(0, 0, 1280, 720), CarViewport.fit(1280, 720, null, null))
    }
}
