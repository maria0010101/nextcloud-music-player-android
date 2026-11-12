# 圖示滿版修正（2026-11-12）

維持 0.9.1 / versionCode 16；這是本機修正版，未替換公開 GitHub Release。

## 原因與修正

- 舊 adaptive 前景含透明留白，透出了舊版深藍色漸層背景。
- 使用原始 `Nextcloud_player.png`，去除圖案之外的透明雜邊，放大可見圖案。
- 從原圖的空白淺色背景延伸出滿版背景；各密度前景均為不透明，
  中央 72dp 保留圖案，外圍補滿 108dp 的自適應圖層。
- 背景 fallback 同步改成原圖淺藍色，不再保留舊的深藍漸層。
- 移除以彩色前景兼作 monochrome 的宣告，避免滿版 alpha 在主題圖示中變成實心方塊。
- 一般與圓形 legacy 圖示使用相同可見區域；實際外形由 Launcher 遮罩處理。
- 原圖與 ImageMagick 重建程序保存在 `tools/icon-source/` 與
  `tools/generate-launcher-icons.sh`，不需要圖片生成服務。

## 驗證

- `:app:assembleRelease :app:assembleDebug` 成功。
- 各密度共 15 張 launcher PNG 的 opaque 檢查通過。
- Release APK 為 0.9.1 / 16，minSdk 26，沿用既有簽章。
- 手機 Xiaomi 2109119DG 以 `adb install -r` 覆蓋安裝 debug 成品成功；
  桌面截圖確認淺色圖示滿版、沒有深藍外圈。未清除資料或卸載。
- 安裝前後 10 個資料表逐列雜湊完全一致：1,491 張專輯、15,948 首曲目。
- 此次只變更圖示資源，沒有更動播放器／音量實作。

Release APK SHA-256：
`ecc45b987aca38b235c99515ed81381083ed19cbe845e927f251d533440b48b4`

APK、build log、桌面截圖及資料比對：workspace
`artifacts/nextcloud-icon-20261112/`。其中 `private/` 是私有資料備份，
不可上傳或公開。
