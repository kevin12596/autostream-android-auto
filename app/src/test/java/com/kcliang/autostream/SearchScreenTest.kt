package com.kcliang.autostream

import android.app.Application
import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.serialization.Bundleable
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises real Car App templates and host callbacks; actual dictation UI is owned by the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SearchScreenTest {
    private lateinit var context: TestCarContext
    private lateinit var controller: CarDisplayController
    private lateinit var video: VideoScreen
    private lateinit var screens: TestScreenManager
    private val done = object : OnDoneCallback {
        override fun onFailure(response: Bundleable) {
            fail("Host callback failed: $response")
        }
    }

    @Before fun setUp() {
        context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        WebViewConfig.prefs(context).edit().clear().commit()
        screens = context.getCarService(TestScreenManager::class.java)
        context.lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        controller = CarDisplayController(context)
        video = VideoScreen(context, controller)
        screens.push(video)
    }

    @After fun tearDown() {
        controller.release()
    }

    @Test fun searchAndBackActionsRequestNativeKeyboard() {
        val template = video.onGetTemplate() as NavigationTemplate
        assertEquals(3, template.actionStrip!!.actions.size)
        assertEquals("搜尋", template.actionStrip!!.actions.first { it.title?.toString() == "搜尋" }.title!!.toString())
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        assertTrue(input.isShowKeyboardByDefault)
        assertEquals(Action.APP_ICON, input.headerAction)
    }

    @Test fun originalPageActionCanRestoreAndReenableWideLayout() {
        val template = video.onGetTemplate() as NavigationTemplate
        template.actionStrip!!.actions.first { it.title?.toString() == "原生頁面" }.onClickDelegate!!.sendClick(done)
        idle()
        assertFalse(controller.wideLayoutEnabled)
        val original = video.onGetTemplate() as NavigationTemplate
        original.actionStrip!!.actions.first { it.title?.toString() == "雙欄播放" }.onClickDelegate!!.sendClick(done)
        idle()
        assertTrue(controller.wideLayoutEnabled)
    }

    @Test fun typingChangesPromptButNavigatesOnlyAfterSubmit() {
        val search = openSearch()
        var input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchTextChanged("爵士樂", done)
        idle()
        input = search.onGetTemplate() as SearchTemplate
        assertTrue((input.itemList!!.items.single() as Row).title!!.toString().contains("爵士樂"))
        assertSame(search, screens.top)
        input.searchCallbackDelegate.sendSearchSubmitted("爵士樂", done)
        idle()
        assertSame(video, screens.top)
        // With no car surface yet, the submitted search must survive until the WebView is created.
        assertEquals(YouTubeSearch.url("爵士樂"), controller.initialUrl())
    }

    @Test fun dictatedSubmissionWorksWithoutAnyTypingCallback() {
        val search = openSearch()
        (search.onGetTemplate() as SearchTemplate).searchCallbackDelegate
            .sendSearchSubmitted("  周杰倫 晴天  ", done)
        idle()
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("周杰倫 晴天"), controller.initialUrl())
        assertEquals("周杰倫 晴天", (openSearch().onGetTemplate() as SearchTemplate).initialSearchText)
    }

    @Test fun tappingSearchRowSubmitsCurrentQuery() {
        val search = openSearch()
        (search.onGetTemplate() as SearchTemplate).searchCallbackDelegate.sendSearchTextChanged("R&B", done)
        idle()
        val row = (search.onGetTemplate() as SearchTemplate).itemList!!.items.single() as Row
        row.onClickDelegate!!.sendClick(done)
        idle()
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("R&B"), controller.initialUrl())
    }

    @Test fun dictatedTextSearchesAfterQuietPeriodWithoutSubmitCallback() {
        val search = openSearch()
        (search.onGetTemplate() as SearchTemplate).searchCallbackDelegate.sendSearchTextChanged("你好你好", done)
        idle()
        assertSame(search, screens.top)
        shadowOf(Looper.getMainLooper()).idleFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("你好你好"), controller.initialUrl())
    }

    @Test fun newerDictationReplacesPartialTextAndBackCancelsAutoSubmit() {
        val before = controller.initialUrl()
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchTextChanged("你好", done)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        input.searchCallbackDelegate.sendSearchTextChanged("你好世界", done)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertSame(search, screens.top)
        context.onBackPressedDispatcher.onBackPressed()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertSame(video, screens.top)
        assertEquals(before, controller.initialUrl())
    }

    @Test fun explicitSearchButtonWorksWithoutKeyboardSubmitCallback() {
        val search = openSearch()
        (search.onGetTemplate() as SearchTemplate).searchCallbackDelegate.sendSearchTextChanged("交響樂", done)
        idle()
        (search.onGetTemplate() as SearchTemplate).actionStrip!!.actions.last().onClickDelegate!!.sendClick(done)
        idle()
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("交響樂"), controller.initialUrl())
    }

    @Test fun homeActionCancelsAutomaticSearchAndIgnoresLateHostSubmission() {
        controller.search("先前查詢")
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchTextChanged("尚未送出", done)
        idle()
        val home = (search.onGetTemplate() as SearchTemplate).actionStrip!!.actions.first()
        assertEquals("首頁", home.title!!.toString())
        home.onClickDelegate!!.sendClick(done)
        idle()
        assertSame(video, screens.top)
        input.searchCallbackDelegate.sendSearchSubmitted("遲到語音", done)
        shadowOf(Looper.getMainLooper()).idleFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(WebViewConfig.HOME_URL, controller.initialUrl())
    }

    @Test fun hostCanSubmitAnEmptyFinalStringAfterSendingDraftText() {
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchTextChanged("台灣爵士", done)
        idle()
        input.searchCallbackDelegate.sendSearchSubmitted("", done)
        idle()
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("台灣爵士"), controller.initialUrl())
    }

    @Test fun clearingTheDraftBeforeSubmittingDoesNotSearchOldText() {
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchTextChanged("清除", done)
        idle()
        input.searchCallbackDelegate.sendSearchTextChanged("", done)
        idle()
        input.searchCallbackDelegate.sendSearchSubmitted("", done)
        idle()
        assertSame(search, screens.top)
    }

    @Test fun backCancelsInputWithoutNavigatingTheWebView() {
        val before = controller.initialUrl()
        val search = openSearch()
        (search.onGetTemplate() as SearchTemplate).searchCallbackDelegate.sendSearchTextChanged("取消測試", done)
        idle()
        context.onBackPressedDispatcher.onBackPressed()
        idle()
        assertSame(video, screens.top)
        assertEquals(before, controller.initialUrl())
        assertEquals("", (openSearch().onGetTemplate() as SearchTemplate).initialSearchText)
    }

    @Test fun blankOrLateDuplicateSubmitDoesNotNavigate() {
        val search = openSearch()
        val input = search.onGetTemplate() as SearchTemplate
        input.searchCallbackDelegate.sendSearchSubmitted("\u3000 ", done)
        idle()
        assertSame(search, screens.top)
        input.searchCallbackDelegate.sendSearchSubmitted("第一次", done)
        idle()
        input.searchCallbackDelegate.sendSearchSubmitted("重複回呼", done)
        idle()
        assertSame(video, screens.top)
        assertEquals(YouTubeSearch.url("第一次"), controller.initialUrl())
    }

    private fun openSearch(): SearchScreen {
        (video.onGetTemplate() as NavigationTemplate).actionStrip!!.actions.first { it.title?.toString() == "搜尋" }.onClickDelegate!!.sendClick(done)
        idle()
        return screens.top as SearchScreen
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
