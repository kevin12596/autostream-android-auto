# 維護與發布

此來源快照不含舊 Git 歷史、私人金鑰或 APK。作者已選用 Apache-2.0，見根目錄 LICENSE。第三方依賴與素材仍依各自授權；專案授權不授予 Google、YouTube 或 BMW 商標權。

## 新 repo 匯入

建立空白 repo，不要預先建立 README／LICENSE。解壓此資料夾後執行 git init -b main、git add .、git commit，再設定新 repo 的 origin 並 push。不要從私人 repo 推送分支或 tags。repo 名稱決定後，更新文件下載連結。

## 建置

JDK 17、Android SDK 35，執行 ./gradlew testDebugUnitTest assembleDebug。CI 會另跑 Chromium 版面、快轉與捲動測試。沒有簽章 Secrets 時產生本機／臨時 debug key 的測試 APK，不能保證覆蓋正式版；fork 的 APK 亦不能當成官方更新。

## 官方簽章

在新 repo Settings → Secrets and variables → Actions 設定 AUTOSTREAM_KEYSTORE_BASE64、AUTOSTREAM_STORE_PASSWORD、AUTOSTREAM_KEY_ALIAS、AUTOSTREAM_KEY_PASSWORD。只透過 GitHub 設定介面輸入；不要放在 issue、文件或 commit。工作流程只在暫存目錄解碼金鑰。切勿將不受信任的 PR 程式碼與官方 Secrets 一起執行。

舊金鑰若一直保持私人，可由作者將它安全移入 Secrets 延續更新；若曾外洩則需新金鑰及遷移計畫，新簽章通常不能直接覆蓋舊安裝。不要刪除既有使用者資料來掩蓋簽章差異。

## 發布

此工作流程不自動發布，避免把臨時 debug 簽章 APK 誤當正式更新。先確認官方 Secrets 已設定、CI 全綠，下載 artifact 並驗證版本、雜湊與 apksigner 簽章。建立對應 commit 的 GitHub Release，附 APK、SHA-256、更新說明及安裝教學。手動觸發 Build 並勾選 publish 才會嘗試發布，；首次 v0.4.14 可改用 distribution 內指定雜湊的既有官方 APK，後續新版仍需正式簽章 Secrets。

## 隱私與限制

登入 Cookie 與播放資訊保存在 App 的 WebView／本機設定；YouTube、Google 與語音辨識服務仍依各自政策處理資料。公開前另行檢查紀錄、備份、權限及第三方網路請求，本文不是完整隱私稽核。實際車型支援依使用者回報，不宣稱通過 BMW 或 Android Auto 官方認證。
