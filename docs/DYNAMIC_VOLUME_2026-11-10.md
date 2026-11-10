# 動態音量與圖示更新（2026-11-10）

版本：0.9.1-flick-dev，versionCode 15；延續 feature/flick-rust-audio。

## 實作

- 25／50／100 段改由 MusicPlaybackService 統一管理：細分比例同步至
  AudioManager.STREAM_MUSIC，Player 只套用相鄰階數之間的殘餘增益。
- 使用 Android 的 getStreamVolumeDb 曲線，在相鄰階數之間以 dB 內插，
  不將系統的整數階數誤當作線性振幅。
- **取整限制**：Media3 Player 的音量上限為 1，不能提供正增益補償。
  因此使用最近的「不低於目標」整數階數（ceil），再衰減不足一階的 dB；
  並非一般四捨五入後正負放大。這讓滿刻度 PCM 不需放大／截波。
- 靜音為系統階數 0／Player gain 0。最低非零階的 -Infinity 曲線端點
  以第一個有限階再低 6 dB 處理。API26／27 或無有效曲線時使用估算曲線。
- Activity 前景／背景轉換只更新顯示，不改系統音量或 Player gain。
  偏好變更由服務監聽，背景系統音量事件則更新細分比例並移除舊殘餘衰減。
  背景實體按鍵仍採用 Android 的整數步幅，不宣稱全域攔截成細分步幅。
- 第一次從舊版本移轉或從關閉模式啟用時，以目前系統音量初始化；
  不把舊 App-local 100% 直接套到全域系統音量。服務重建時若系統階數
  仍與保存值一致，保留細分比例與殘餘增益。
- 關閉模式為 unity、系統音量為主。bit-perfect 完全不進行動態映射，
  不主動改 AudioManager，仍由相容 USB DAC 控制音量。
- 輸出裝置變動時重新計算；系統安全音量／固定音量限制不以軟體放大繞過。
- 使用指定的 Nextcloud_player.png 產生各密度一般／圓形／adaptive 前景圖示。

## 驗證

- Gradle debug APK、instrumentation APK 建置成功。
- Kotlin/JVM：24 項通過，包含新增的 15／25／30 系統階數 ×
  25／50／100 細分、非線性 dB 曲線、合成增益一致、單調性、靜音及無效曲線測試。
- 實機：Xiaomi 2109119DG，Android14。新增測試通過：
  原生與 Rust 均以 120 秒靜音 WAV 持續播放，驗證 21%、43%、67%、0%
  映射；每組按 HOME 再返回，系統階數與 Player gain 不變且背景仍在播放。
  背景調整系統音量後，確認偏好及 unity 補償同步，返回前景不覆寫。
  另確認關閉及 bit-perfect 模式隔離。
- 實體按鍵回歸測試通過：關閉／Rust細分／bit-perfect。
- 透過 adb install -r 更新，未移除 App 或清除資料。
- 安裝前後 albums／tracks／playlists／playlist_tracks 逐列雜湊一致：
  1,491 張專輯、15,948 首曲目，schema 未更改。
- 測試使用靜音檔；音量與偏好於 finally 還原。無類比聲壓／音質量測。

## 尚未實測

USB DAC、藍牙／多路輸出、其他 Android 版本及廠商曲線差異未實機驗證。
裝置類型是依已連接輸出推定；同時存在多個可用輸出時，曲線可能不等於
實際路由曲線，需進一步按目標設備驗收。嚴格 USB bit-perfect 的硬體驗收
仍保留為前次整合的待驗證項目。

APK SHA-256：
`dd0fe00ab1d9b8e24494142002e5e59524a02540a893716d107bab1154bbcd78`

APK、測試原始輸出、資料比對及本機私有備份位於 workspace artifacts 的
nextcloud-dynamic-20261110 目錄。私有備份不應提交版本庫或公開。
