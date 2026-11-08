package com.kcliang.autostream

import android.app.Activity
import android.app.Application
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PhoneInsetsTest {
    @Test fun systemBarsAndCutoutDoNotAccumulatePaddingAcrossUpdates() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(LinearLayout(activity))
            val content = activity.findViewById<android.view.View>(android.R.id.content)
            PhoneInsets.install(activity)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 16))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 40, 0, 0)).build()
            repeat(3) { ViewCompat.dispatchApplyWindowInsets(content, insets) }
            assertEquals(40, content.paddingTop)
            assertEquals(16, content.paddingBottom)
            ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 16)).build())
            assertEquals(24, content.paddingTop)
        } finally { controller.pause().stop().destroy() }
    }
}
