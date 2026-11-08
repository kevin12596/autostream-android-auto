# 官方預建置 APK

此處僅保存首次公開發行的 v0.4.14/build18，來自既有私人 CI 的已驗證產物，沿用舊簽章。不是新 repo 臨時 debug key 編譯的 APK。金鑰不包含在此儲存庫；APK 內的公開憑證無法用來簽署更新。

SHA-256: `20ed50c9c89f817b08abe4eca02adb3cde670b1cc28db924b58241cd59c4120c`

簽章憑證 SHA-256: `dab7d9c9389f6dc12992655caeb4de812775dab39440e50478bdebd1953374c2`

首次發布工作流程在尚未設定 Secrets 時只允許這個指定雜湊的預建置檔，避免把 CI 臨時簽章當正式更新。後續版本應由維護者設定官方簽章 Secrets 再發布。
