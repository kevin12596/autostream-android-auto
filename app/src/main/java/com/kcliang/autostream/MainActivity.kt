package com.kcliang.autostream

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Phone-side setup screen. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "AutoStream 版本 v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            setPadding(0, 0, 0, pad)
        })
        col.addView(button("搜尋 YouTube（鍵盤／語音）") {
            startActivity(Intent(this, PhoneSearchActivity::class.java))
        })
        col.addView(TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            text = """
                AutoStream 會在 Android Auto 車機畫面上顯示 YouTube（WebView）。

                手機搜尋：按上方「搜尋 YouTube」，可用鍵盤或語音輸入，選擇在手機搜尋或傳送到已開啟 AutoStream 的車機。

                設定步驟：
                1. 先按「登入 Google / YouTube」完成一次性登入（Cookie 會保存並與車機共用）。
                2. 允許通知（前景服務需要）。
                3. 關閉電池最佳化；ColorOS 另需允許背景活動並在最近任務中鎖定本 App。
                4. Android Auto 開啟開發者模式並允許「未知來源」。

                車機搜尋：按車機上方「搜尋」，輸入關鍵字後按搜尋頁上的「搜尋」或下方搜尋列。鍵盤與語音輸入介面由 Android Auto 提供，鍵盤僅在停車時可用。

                播放操作：直接操作網頁上的播放、暫停、進度與全螢幕按鈕。影片左側連點兩下回溯 10 秒，右側連點兩下快進 10 秒。影音同步與畫質上限可在本手機 App 調整。切到其他車機 App（如 Google Maps）聲音會繼續播放。
            """.trimIndent()
        })
        status = TextView(this).apply { setPadding(0, pad, 0, pad) }
        col.addView(status)

        col.addView(button("登入 Google / YouTube（一次性）") {
            startActivity(Intent(this, LoginActivity::class.java))
        })
        col.addView(button("允許通知") { requestNotifications() })
        col.addView(button("電池最佳化設定") { openBatterySettings() })
        col.addView(button("應用程式設定（背景活動 / 自動啟動）") { openAppDetails() })
        col.addView(android.widget.Switch(this).apply {
            text = "儀表板媒體整合（實驗性，預設關；播放立刻暫停時請保持關閉）"
            isChecked = WebViewConfig.clusterMediaEnabled(this@MainActivity)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> WebViewConfig.setClusterMediaEnabled(this@MainActivity, on) }
        })
        addQualitySettings(col, pad)
        addAvSyncSettings(col, pad)

        col.addView(button("登出（清除 Cookie）") {
            CookieManager.getInstance().removeAllCookies { WebViewConfig.flushCookies() }
            Toast.makeText(this, "已清除 Cookie", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { addView(col) })
        PhoneInsets.install(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val notif = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
        status.text = "通知權限：${if (notif) "✅ 已允許" else "❌ 未允許"}\n" +
            "電池最佳化：${if (ignoring) "✅ 已關閉" else "⚠️ 仍在最佳化"}"
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            Toast.makeText(this, "此 Android 版本不需要", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun sectionTitle(label: String, pad: Int) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, pad, 0, pad / 4)
    }

    private fun hint(label: String) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.7f
    }

    private fun addQualitySettings(col: LinearLayout, pad: Int) {
        col.addView(sectionTitle("畫質", pad))
        val options = listOf(
            YouTubeJs.QUALITY_MAX to "最高（預設）",
            YouTubeJs.QUALITY_1080 to "1080p",
            YouTubeJs.QUALITY_720 to "720p",
            YouTubeJs.QUALITY_AUTO to "自動（交給 YouTube）",
        )
        val current = WebViewConfig.qualityCap(this)
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        options.forEachIndexed { i, (cap, label) ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = label
                isChecked = cap == current
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = checkedId - 1000
            if (idx in options.indices) WebViewConfig.setQualityCap(this, options[idx].first)
        }
        col.addView(group)
        col.addView(hint("每次載入影片時鎖定到可用的最高畫質（不超過上限）。車機實際解析度另受 Android Auto 開發人員設定「影片解析度」與車機本身限制。"))
    }

    private fun addAvSyncSettings(col: LinearLayout, pad: Int) {
        col.addView(sectionTitle("影音同步（影片延遲）", pad))
        val step = WebViewConfig.AV_DELAY_STEP_MS
        val label = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        fun render(ms: Int) {
            label.text = if (ms == 0) "0 ms（不延遲）" else "$ms ms"
        }
        val seek = SeekBar(this).apply {
            max = WebViewConfig.AV_DELAY_MAX_MS / step
            progress = WebViewConfig.avDelayMs(this@MainActivity) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    render(value * step)
                    if (fromUser) WebViewConfig.setAvDelayMs(this@MainActivity, value * step)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        render(seek.progress * step)
        col.addView(label)
        col.addView(seek)
        col.addView(hint("無線 Android Auto 聲音常比畫面慢。若嘴型比聲音先動 → 加大延遲。調整會即時套用並保存。"))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIF = 1
    }
}
