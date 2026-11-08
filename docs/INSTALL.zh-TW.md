# AutoStream 安裝與使用教學

AutoStream 是將 YouTube 網頁顯示在 Android Auto 的實驗性側載 App。請在停妥車輛時觀看及操作。它不是 BMW、Google 或 YouTube 官方產品，不保證每款手機、車機及 Android Auto 更新後皆可使用。

## 先確認設備

- Android 手機，App 最低 Android 8；實際 Android Auto 與安裝工具的系統需求可能更高。
- 車輛支援 Android Auto，且手機已能正常連線；iPhone／CarPlay 不適用。
- 手機有網路。實際測試回饋來自 BMW iX，其他車型尚待驗證。
- 第一次安裝預留約 20–30 分鐘。免 root 路徑通常需要 Android 11 以上的無線偵錯與 Shizuku；舊手機請參閱工具官方說明，不要套用不相容步驟。

## 1. 取得 APK 與工具

1. 到專案的 首頁的 Releases 區塊，確認版本為 **v0.4.14 / build 18**。請只使用維護者正式提供的 APK。
2. Release 的 Assets 若提供 `.apk`，直接下載；若是 Actions 的 artifact ZIP，先解壓再取出 APK。不要下載「Source code」當成安裝檔。
3. 安裝 [Shizuku](https://shizuku.rikka.app/guide/setup/) 與 [InstallWithOptions](https://github.com/zacharee/InstallWithOptions)。只從官方來源取得，版本選項名稱可能不同。

公開 Release 的 APK 可直接下載；Actions artifact 通常需要登入 GitHub。

## 2. 啟動 Shizuku（Android 11+，免電腦路徑）

1. 手機連上 Wi-Fi。設定 → 關於手機 → 連點「版本號／組建號碼」，開啟手機開發人員選項；系統可能要求解鎖密碼。
2. 在手機的開發人員選項開啟「無線偵錯」。**這與 Android Auto 的開發人員模式是兩個不同設定。**
3. 開啟 Shizuku，依「透過無線偵錯啟動」指示配對：到無線偵錯選「使用配對碼配對裝置」，將顯示的配對碼輸入 Shizuku 通知或其配對介面。
4. 回 Shizuku 按啟動，確認顯示正在執行。若失敗，依官方教學檢查配對、通知權限及廠牌限制。
5. 開啟 InstallWithOptions，允許它使用 Shizuku。

不需要把配對碼、手機 IP 或任何帳號密碼傳給作者。若工具畫面與本教學不同，以工具官方說明為準。

## 3. 透過 InstallWithOptions 安裝

1. 在 InstallWithOptions 選取 `AutoStream-v0.4.14-build18.apk`。
2. 找到安裝來源／Installer package name，填入 **`com.android.vending`**。它是 Google Play 的套件名稱；指定此欄位不代表 App 經過 Google Play 審核。
3. 執行安裝，確認成功。不要改用檔案管理員直接點 APK，否則 Android Auto 可能不顯示。
4. 手機若要求「允許安裝未知應用程式」，只對實際使用的安裝工具授權。
5. 手機開啟 AutoStream，確認顯示 **v0.4.14 (build 18)**。

已有舊版時，同簽章可直接覆蓋。先不要解除安裝，以免丟失登入和設定。遇到簽章不符應先確認 APK 來源，不要為了安裝而直接刪除資料。

## 4. 讓 Android Auto 顯示 AutoStream

1. 手機設定搜尋「Android Auto」，進入其設定。
2. 找到版本資訊，連點版本約 10 次，確認開啟開發人員模式。
3. 右上角選單 → 開發人員設定 → 勾選「不明來源／Unknown sources」。這與手機允許安裝 APK 的權限不同。
4. 回 Android Auto → 自訂啟動器，勾選 AutoStream。
5. 斷開再連上車機，在 App 清單開啟 AutoStream。若啟動器尚未列出，可先連車一次後重新檢查。

## 5. 第一次使用

- 手機開 AutoStream；如需帳號內容，可選「登入 Google / YouTube」。Google 可能阻擋內嵌瀏覽器登入；不要忽略帳號安全提示，未登入也可先測試。
- 依需要允許通知，將 AutoStream 電池用量設為不受限制。OPPO 等品牌可能另需允許背景活動與自動啟動。
- 中文搜尋建議用手機：AutoStream → 搜尋 YouTube → 鍵盤或語音 → 在手機搜尋；車機已連線且開啟 AutoStream 時，點選影片可送到車機。也可用「傳送搜尋到車機」傳送關鍵字。
- 車機搜尋的鍵盤語言、麥克風與輸入法由 Android Auto 管理，不能保證提供注音；手機端較可靠。
- 雙欄模式左側影片、右側推薦／播放清單。要捲清單先點右欄分頁或切「清單操作」；可直接用上一頁／下一頁。點影片恢復影片操作。
- 「原生頁面」恢復完整網頁；「雙欄播放」切回雙欄，選擇會保存。
- 影片左／右側中段雙擊：回溯／快進 10 秒。直播或廣告可能不支援。
- 全螢幕保留比例；超寬車機播放 4:3 或 16:9 內容有黑邊是正常現象。無法覆蓋 BMW 空調列或 Android Auto 系統列。
- 聲音比畫面慢時，到手機調整「影音同步」，每次增加 50ms 測試。儀表板媒體整合是預設關閉的實驗功能，播放異常時保持關閉。

## 6. 裝好後哪些可以關閉？更新怎麼做？

Shizuku 主要協助安裝，不是 AutoStream 播放的必要背景服務。完成安裝並確認車機看得到後，可停止 Shizuku、關閉無線／USB 偵錯，並撤回安裝工具的未知來源安裝權限，再斷線重連確認。若 Shizuku 被設為自動重啟，可另行停用。**Android Auto 自己的「不明來源」先維持開啟。** 不保證所有手機與 AA 版本行為相同。

更新時再次啟動 Shizuku，用 InstallWithOptions、相同來源 `com.android.vending` 覆蓋安裝。手機重新開機後，Shizuku 通常需要重新啟動。直接點新版 APK 安裝可能改變來源資訊，造成車機清單消失。

## 常見問題

| 問題 | 先做什麼 |
| --- | --- |
| 手機有 App，車機找不到 | 確認 AA 不明來源、啟動器勾選，以及本次更新是否仍用指定來源安裝；必要時用同一 APK 覆蓋重裝，斷線重連 |
| 工具顯示 installing installer 為 packageinstaller | 可作診斷線索，並非 AA 是否可用的保證；確認 InstallWithOptions 的來源欄位及安裝結果 |
| 關掉 Shizuku 後消失 | 先確認是否同時更新、改安裝方式或停用了 AA 不明來源；停止 Shizuku 本身通常不會重新寫入安裝來源 |
| 中文／語音在車機不可用 | 改用手機搜尋並傳到車機；回報手機及 AA 版本 |
| 播放立即暫停 | 保持儀表板媒體整合關閉，檢查其他音訊 App、通知及背景限制 |
| 黑邊／影片不滿版 | 先區分比例留白與裁切；確認版本、雙欄／全螢幕模式，附停車拍攝的照片 |
| 無法登入 | 可能是 Google 的 WebView 登入限制，不保證能排除；先用未登入模式測試 |

回報請提供手機型號、Android／Android Auto／AutoStream 版本、車型、有線或無線、重現步驟。截圖請遮住帳號、通知、位置及車牌。請勿上傳 Cookie、登入密碼、配對碼或含個資的完整紀錄。
