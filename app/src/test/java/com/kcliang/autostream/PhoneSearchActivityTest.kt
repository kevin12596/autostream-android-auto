package com.kcliang.autostream

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.car.app.testing.TestCarContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhoneSearchActivityTest {
    private lateinit var activityController: ActivityController<PhoneSearchActivity>
    private lateinit var activity: PhoneSearchActivity

    @Before fun setUp() {
        activityController = Robolectric.buildActivity(PhoneSearchActivity::class.java).setup()
        activity = activityController.get()
    }

    @After fun tearDown() { activityController.pause().stop().destroy() }

    @Test fun typedChineseQuerySearchesOnPhone() {
        query().setText("中文爵士 R&B")
        activity.findViewById<Button>(R.id.phone_search_submit).performClick()
        assertEquals(YouTubeSearch.url("中文爵士 R&B"), shadowOf(web()).lastLoadedUrl)
    }

    @Test fun keyboardSearchActionSubmitsQuery() {
        query().setText("鋼琴")
        query().onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        assertEquals(YouTubeSearch.url("鋼琴"), shadowOf(web()).lastLoadedUrl)
    }

    @Suppress("DEPRECATION")
    @Test fun voiceFillsEditableQueryThenUsesTheSameSearch() {
        activity.findViewById<Button>(R.id.phone_search_voice).performClick()
        val speech = shadowOf(activity).nextStartedActivityForResult
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, speech.intent.action)
        val response = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("語音搜尋音樂"))
        shadowOf(activity).receiveResult(speech.intent, Activity.RESULT_OK, response)
        assertEquals("語音搜尋音樂", query().text.toString())
        activity.findViewById<Button>(R.id.phone_search_submit).performClick()
        assertEquals(YouTubeSearch.url("語音搜尋音樂"), shadowOf(web()).lastLoadedUrl)
    }

    @Test fun phoneCanSendQueryToCarWithoutOpeningItsOwnResults() {
        val car = CarDisplayController(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()))
        try {
            query().setText("傳送到車機")
            activity.findViewById<Button>(R.id.phone_search_car).performClick()
            assertEquals(YouTubeSearch.url("傳送到車機"), car.initialUrl())
            assertEquals(WebViewConfig.HOME_URL, shadowOf(web()).lastLoadedUrl)
        } finally { car.release() }
    }

    @Suppress("DEPRECATION")
    @Test fun selectingVideoForwardsToCarWithoutStartingAnotherPhonePlayer() {
        val car = CarDisplayController(TestCarContext.createCarContext(RuntimeEnvironment.getApplication()))
        try {
            val url = "https://m.youtube.com/watch?v=abcdefghijk&list=PL123"
            assertTrue(web().webViewClient.shouldOverrideUrlLoading(web(), url))
            assertEquals(url, car.initialUrl())
            assertEquals(WebViewConfig.HOME_URL, shadowOf(web()).lastLoadedUrl)
        } finally { car.release() }
    }

    @Suppress("DEPRECATION")
    @Test fun selectingVideoWithoutCarKeepsNormalPhoneNavigation() {
        assertFalse(web().webViewClient.shouldOverrideUrlLoading(web(), "https://m.youtube.com/watch?v=abcdefghijk"))
    }

    @Test fun blankQueryLeavesPageInPlaceAndShowsInputError() {
        query().setText("  ")
        activity.findViewById<Button>(R.id.phone_search_submit).performClick()
        assertNotNull(query().error)
        assertEquals(WebViewConfig.HOME_URL, shadowOf(web()).lastLoadedUrl)
    }

    @Test fun sendingWithoutCarExplainsHowToConnect() {
        query().setText("未連線")
        activity.findViewById<Button>(R.id.phone_search_car).performClick()
        assertTrue(activity.findViewById<TextView>(R.id.phone_search_status).text.toString().contains("尚未連上"))
    }

    @Test fun mainScreenHasAnEntryToPhoneSearch() {
        val setup = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val views = arrayListOf<android.view.View>()
            setup.get().window.decorView.findViewsWithText(views, "搜尋 YouTube", android.view.View.FIND_VIEWS_WITH_TEXT)
            views.filterIsInstance<Button>().single().performClick()
            assertEquals(PhoneSearchActivity::class.java.name,
                shadowOf(setup.get()).nextStartedActivity.component!!.className)
        } finally { setup.pause().stop().destroy() }
    }

    private fun query() = activity.findViewById<EditText>(R.id.phone_search_query)
    private fun web() = activity.findViewById<WebView>(R.id.phone_search_web)
}
