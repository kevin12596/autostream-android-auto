package com.kcliang.autostream

import android.app.Application
import android.hardware.display.DisplayManager
import android.view.Display
import android.webkit.WebView
import androidx.car.app.testing.TestCarContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Reproduces returning from native search while an existing WebView is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SearchNavigationTest {
    private lateinit var context: TestCarContext
    private lateinit var controller: CarDisplayController

    @Before fun setUp() {
        context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        WebViewConfig.prefs(context).edit().clear().commit()
        PlaybackStore.save(context, "oldvideo123", 145.0)
        controller = CarDisplayController(context)
    }

    @After fun tearDown() { controller.release() }

    @Test fun searchWithExistingWebViewSurvivesReplacementBeforeLoadCompletes() {
        val old = installPresentation()
        controller.search("新搜尋 爵士")
        val target = YouTubeSearch.url("新搜尋 爵士")
        assertEquals(target, shadowOf(old).lastLoadedUrl)
        // This used to restore oldvideo123 because search only queued when the WebView was null.
        val replacement = installPresentation()
        assertEquals(target, shadowOf(replacement).lastLoadedUrl)
        assertEquals(target, controller.initialUrl())
    }

    @Test fun readingInitialUrlDoesNotConsumeAnUnfinishedSearch() {
        controller.search("尚未載入")
        repeat(3) { assertEquals(YouTubeSearch.url("尚未載入"), controller.initialUrl()) }
    }

    @Test fun completedResultsStayCurrentInsteadOfRestoringOldVideo() {
        val web = installPresentation()
        controller.search("已載入結果")
        val target = YouTubeSearch.url("已載入結果")!!
        controller.onPageFinished(web, target)
        assertEquals(target, WebViewConfig.lastUrl(context))
        assertEquals(target, controller.initialUrl())
    }

    @Test fun aLateCallbackFromOldWebViewDoesNotConsumeNewSearch() {
        val old = installPresentation()
        controller.search("第一次")
        val oldTarget = YouTubeSearch.url("第一次")!!
        installPresentation()
        controller.search("第二次")
        controller.onPageFinished(old, oldTarget)
        assertEquals(YouTubeSearch.url("第二次"), controller.initialUrl())
    }

    @Test fun phoneBridgeFindsActiveCarAndDisconnectsOnRelease() {
        assertTrue(CarSearchBridge.search("手機送出"))
        assertEquals(YouTubeSearch.url("手機送出"), controller.initialUrl())
        controller.release()
        assertFalse(CarSearchBridge.search("已斷線"))
    }

    @Test fun watchResumeStillWorksWhenNoSearchIsActive() {
        assertEquals(YouTubeJs.watchUrl("oldvideo123", 145), controller.initialUrl())
    }

    @Test fun originalPageChoicePersistsAcrossCarSessions() {
        controller.setWideLayout(false)
        controller.release()
        controller = CarDisplayController(context)
        assertFalse(controller.wideLayoutEnabled)
    }

    @Test fun explicitHomeReplacesPendingSearchAndSurvivesRebuildAfterLoad() {
        val web = installPresentation()
        controller.search("待載入")
        controller.goHome()
        assertEquals(WebViewConfig.HOME_URL, shadowOf(web).lastLoadedUrl)
        controller.onPageFinished(web, WebViewConfig.HOME_URL)
        assertEquals(WebViewConfig.HOME_URL, WebViewConfig.lastUrl(context))
        assertEquals(WebViewConfig.HOME_URL, shadowOf(installPresentation()).lastLoadedUrl)
    }

    @Test fun hostSafeAreaIsAppliedToWebViewRatherThanDrawingUnderOverlays() {
        val web = installPresentation()
        val presentation: CarPresentation = ReflectionHelpers.getField(controller, "presentation")
        presentation.applyContentBounds(android.graphics.Rect(80, 40, 1200, 600))
        val params = web.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(80, params.leftMargin)
        assertEquals(40, params.topMargin)
        assertEquals(1120, params.width)
        assertEquals(560, params.height)
        assertEquals(android.graphics.Rect(80, 40, 1200, 600), presentation.contentBounds)
    }

    @Test fun nativeFullscreenSignalsLayoutAndKeepsHostSafeBounds() {
        val web = installPresentation()
        val presentation: CarPresentation = ReflectionHelpers.getField(controller, "presentation")
        presentation.applyContentBounds(android.graphics.Rect(80, 40, 1200, 600))
        val view = android.widget.FrameLayout(context)
        var hidden = 0
        web.webChromeClient!!.onShowCustomView(view, android.webkit.WebChromeClient.CustomViewCallback { hidden++ })
        assertTrue(presentation.isCustomViewActive)
        assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("setFullscreen(true)"))
        val params = view.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(80, params.leftMargin)
        assertEquals(40, params.topMargin)
        assertEquals(1120, params.width)
        assertEquals(560, params.height)
        assertTrue(presentation.exitFullscreen())
        assertFalse(presentation.isCustomViewActive)
        assertNull(view.parent)
        assertEquals(1, hidden)
        assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("setFullscreen(false)"))
        assertFalse(presentation.exitFullscreen())
    }

    @Test fun legacyFocusPreferenceIsRetiredAndClusterMediaIsOptIn() {
        controller.release()
        WebViewConfig.prefs(context).edit().putBoolean("app_audio_focus", true).commit()
        controller = CarDisplayController(context)
        assertFalse(WebViewConfig.prefs(context).contains("app_audio_focus"))
        assertFalse(WebViewConfig.clusterMediaEnabled(context))
        assertNull(ClusterMediaService.commands)
        controller.onBridgeMessage("""{"t":"state","vid":"oldvideo123","playing":true,"time":0.1,"dur":180} """.trim())
        assertNull(ClusterMediaService.commands)
    }

    private fun installPresentation(): WebView {
        val old: CarPresentation? = ReflectionHelpers.getField(controller, "presentation")
        ReflectionHelpers.setField(controller, "presentation", null)
        old?.destroyWebView()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val presentation = CarPresentation(context, display, controller)
        ReflectionHelpers.setField(controller, "presentation", presentation)
        presentation.create()
        return presentation.webView!!
    }
}
