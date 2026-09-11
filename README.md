# Nextcloud WebDAV Music Player for Android

[![Release](https://img.shields.io/github/v/release/maria0010101/nextcloud-music-player-android?style=flat-square&color=blue)](https://github.com/maria0010101/nextcloud-music-player-android/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green?style=flat-square)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20Material%203-4285F4?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Audio-AndroidX%20Media3%20ExoPlayer-orange?style=flat-square)](https://developer.android.com/guide/topics/media/media3)
[![Nextcloud](https://img.shields.io/badge/Cloud-Nextcloud%20WebDAV-0082C9?style=flat-square&logo=nextcloud)](https://nextcloud.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

A modern, native, and lightweight Android music streaming and offline playback application designed specifically for self-hosted **Nextcloud** instances. It streams audio directly over standard **WebDAV** protocols and decodes media entirely on the client device, delivering high-fidelity sound while placing virtually zero computational or transcoding burden on your personal server.

---

## 📖 Overview

Many private cloud music solutions require dedicated server-side extensions (such as Subsonic, Ampache, or Audio Player apps) that continuously index libraries or perform heavy real-time transcoding.

**Nextcloud WebDAV Music Player** takes an elegant, server-agnostic approach:
- **Direct WebDAV Interaction**: Utilizes native Nextcloud WebDAV endpoints (`PROPFIND`, HTTP range `GET`, `PUT`) for folder discovery, progressive streaming, and cover management.
- **Client-Side Decoding**: Powered by AndroidX Media3 (ExoPlayer), decoding FLAC, MP3, AAC, OGG, WAV, ALAC, and Opus right on your phone.
- **Smart Metadata & Image Resolution**: Dynamically discovers album structures and cover art without requiring server modifications.
- **Low Overhead**: Enjoy your private lossless music collection anywhere without heating up your home server or NAS.

---

## 🚀 What's New in v0.7.1

- 📈 **Interactive 10-Band Graphic Equalizer Response Curve (`GraphicEqView`)**:
  - **Visual Frequency Response Polyline Chart (折線圖 / 響應圖)**:
    Replaced the legacy list of 10 individual horizontal sliders with a modern, high-precision visual graphic equalizer interface based on the elegant presentation and ergonomic design by [nulldio/32steps](https://github.com/nulldio/32steps).
  - **Intuitive Frequency (X) & Gain (Y) Mapping**:
    - **Horizontal Axis (X)**: 10 standardized audio frequency bands (`31 Hz`, `62 Hz`, `125 Hz`, `250 Hz`, `500 Hz`, `1 kHz`, `2 kHz`, `4 kHz`, `8 kHz`, `16 kHz`).
    - **Vertical Axis (Y)**: Spans a $-12\text{ dB}$ to $+12\text{ dB}$ dynamic range with horizontal reference grid lines at $+10\text{ dB}$, $+5\text{ dB}$, $0\text{ dB}$ (prominent center reference line), $-5\text{ dB}$, and $-10\text{ dB}$.
  - **Bi-Directional Rounded Gain Bars & Contour Gradient Fill**:
    - Vertical rounded bars dynamically extend upwards (for boost) or downwards (for cut) from the $0\text{ dB}$ baseline directly to each frequency node.
    - Soft, semi-transparent contour gradient fill smoothly shades the area under the response polyline, creating an authentic hardware audio analyzer aesthetic.
  - **Direct Touch & Drag Interaction with Haptic Feedback**:
    - Shape frequency response curves effortlessly by touching and dragging any band node up or down with real-time $0.5\text{ dB}$ micro-step precision.
    - Subtle tactile tick vibration (`HapticFeedbackConstants`) accompanies every $0.5\text{ dB}$ adjustment.
    - Child gesture isolation (`parent.requestDisallowInterceptTouchEvent(true)`) ensures uninterrupted finger dragging without accidental modal bottom sheet or scroll container gesture conflicts.
  - **Double-Tap Quick Zero Reset**:
    - Double-tapping any frequency node instantly resets that specific band back to $0.0\text{ dB}$ without disturbing adjacent bands.
  - **Smart Non-Clipping Decibel Readout & Master Toggle**:
    - Numeric gain labels (e.g., `+4.5`, `0.0`, `-3.5`) intelligently flip above or below node points based on vertical position to eliminate edge clipping.
    - Switching the master EQ toggle smoothly dims the graph and disables touch interaction. The "Reset EQ to Flat" button instantaneously flattens all 10 bands.

---

## 🚀 What's New in v0.7.0

- 🎛️ **Professional Audio DSP Engine & 32steps Architecture Overhaul**:
  - **Burst-Free Continuous Volume Stepping (`VolumeStepManager`)**:
    Completely replaced the legacy `VolumeSyncManager` (which previously forced system volume to maximum in the foreground, causing audio buffer explosions/bursts and sync desynchronization across app switches).
    Adopted the proven architecture of [nulldio/32steps](https://github.com/nulldio/32steps): dynamically maps any custom step count ($N \in \{15, 25, 30, 50, 100\}$) to the natural hardware system volume level ($\lceil \text{fraction} \times \text{sysMax} \rceil$) and applies smooth micro-step DSP software attenuation ($-3.0\text{ dB}$ per fractional system unit). System volume is never forced to max, ensuring 100% burst-free (無爆音), continuous, and seamless volume control across foreground, background, and lockscreen.
  - 🎧 **AutoEq Headphone Sound Profiles (6,000+ Calibrated Models)**:
    - Integrated the comprehensive AutoEq database with over 6,000 calibrated headphone and in-ear monitor (IEM) frequency response profiles bundled offline in `headphones.dat` (gzip JSON, 174 KB).
    - Fast instant search dialog with real-time query filtering, category badges, preamp gain compensation, and one-tap selection.
    - Mathematical logarithmic frequency interpolation (`BiquadMath.interpolateToGrid`) transforming parametric EQ target curves into pre-equalization filters.
  - 📊 **10-Band Graphic Equalizer (`DynamicsProcessing`)**:
    - Hardware-accelerated 10-band graphic equalizer (31 Hz, 62 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, 16 kHz) directly attached to ExoPlayer's `audioSessionId` via Android `DynamicsProcessing` (API 28+) with a fallback to `Equalizer`.
    - 10 curated sound presets: Flat, Bass Boost, Treble Boost, Vocal, Acoustic, Rock, Electronic, Classical, Jazz, and Pop, plus full custom slider control (-12 dB to +12 dB with real-time dB readouts).
    - One-click "Reset EQ to Flat" button for instant reset.
  - ⚖️ **Stereo Channel Balance (Left / Right Panning)**:
    - Dedicated channel balance slider (-1.0 to +1.0) with real-time percentage indicators ("左偏 50%", "右偏 50%", "置中 (平衡)") and quick one-tap "Reset to Center" action.
    - Applied via limiter post-gain channel attenuation in the DSP pipeline.
  - 🎚️ **Floating Volume Overlay HUD & Quick Tune Shortcut**:
    - Right-edge vertical capsule volume slider HUD displaying current step count badge (e.g. `18 / 50`).
    - Embedded Tune shortcut button on the HUD for one-tap access to the Audio & Equalizer modal bottom sheet from anywhere in the app.
    - Also accessible directly from the player toolbar and Settings screen.

---

## 🚀 What's New in v0.6.1

- 🔊 **Acoustic Decibel Calibration & Whisper-Quiet Playback (`stepToFloatVolume`)**:
  - **Logarithmic Decibel Curve**: Replaced linear volume division (`step / maxSteps`) with a human hearing calibrated decibel curve ($amplitude = 10^{\frac{targetDb}{20}}$).
  - **Whisper-Quiet Low-End Output**: Calibrated the minimum audible step (`step 1`) to $-56.0\text{ dB}$ (linear amplitude $\approx 0.00158$), perfectly matching Android's native system step 1 ($\approx -54.1\text{ dB}$). Eliminates the issue where low volume settings previously blasted loudly at $-28\text{ dB}$, enabling delicate, whisper-quiet playback for late-night listening.
  - **Equal Perceptual Step Progression**: Custom steps are distributed linearly in decibels ($\sim 2.3\text{ dB}$ increments for 25 steps; $\sim 1.1\text{ dB}$ for 50 steps), delivering natural, smooth perceived loudness across the entire dynamic range.
- 🛡️ **Staggered Volume Handover & Switching Surge Elimination (`VolumeSyncManager`)**:
  - **Eliminated App-Switching Volume Surges (爆音修復)**: Fixed a momentary volume explosion when switching back to the app from the desktop. Previously, the hardware system volume was set to maximum (`maxSys`) synchronously before ExoPlayer software attenuation could take effect over Binder IPC, causing buffered $1.0\text{f}$ full-amplitude PCM audio to play at 100% hardware gain for 100~150ms.
  - **150ms Buffer Drain Handover**: When entering the foreground, ExoPlayer software attenuation is dispatched first; elevation of the system volume base to `maxSys` is safely delayed by 150ms to allow residual audio in the `AudioTrack` ring buffer to drain at low system gain. Peak acoustic volume during app transition never exceeds the target level.
  - **Bi-Directional Decibel Mapping (`getStreamVolumeDb`)**: Queries the system's actual hardware attenuation curve across speakers, wired headsets, and Bluetooth A2DP via Android `AudioManager` on API 28+ (with an exponential fallback model for earlier versions), ensuring seamless, glitch-free volume parity when transitioning between the app and the desktop.
- 🛡️ **Abnormal Termination & Service Cleanup Safeguards**:
  - **Automatic System Volume Restoration**: In `MusicPlaybackService.onDestroy()` and `VolumeSyncManager.init()`, added detection for ungraceful exits, ensuring the device's system volume is automatically restored to its original level and never left permanently maxed at 150.

---

## 🚀 What's New in v0.6

- 🎚️ **Custom High-Precision Volume Control (25 / 50 Steps)**:
  - **Granular Volume Precision**: Choose between "Default (System 15 Steps)", "High Precision 25 Steps", and "Ultra-High Precision 50 Steps" in Settings, persisted seamlessly via Jetpack DataStore (`volume_steps_key`).
  - **Hardware Key Interception**: Intercepts `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN` in `MainActivity` when in the foreground with 25 or 50 steps active, completely suppressing the default system 15-step volume dialog.
  - **Software Volume Regulation (`PlayerViewModel`)**: Computes exact software attenuation ($floatVolume = currentStep / maxSteps$) and applies it directly to ExoPlayer (`setVolume`).
  - **Auto-Release to System**: Automatically restores native 15 steps when the app is backgrounded or when set to default 15-step mode.
- 📱 **Floating System-Style Volume HUD (`VolumeHud`)**:
  - **Native-Look Capsule Slider**: Sleek vertical pill capsule aligned to the right edge with a live step indicator badge (e.g., `44 / 50`), progressive bottom-to-top track fill, and reactive mute/speaker icons.
  - **Touch & Gesture Dragging**: Directly tap or drag vertically anywhere along the floating slider to adjust volume smoothly in real time.
  - **Smart Transient Animation**: Slides in and fades in smoothly upon physical key press or touch, and automatically fades out after 1.5 seconds of inactivity.
- 🔄 **Bi-Directional System Volume Smoothing & Sync (`VolumeSyncManager`)**:
  - **Foreground Alignment (AudioManager → Steps)**: On entering the foreground (`onStart`), reads the current system media volume, maps it to the custom step, sets the software volume, and elevates the system media stream to maximum (`maxSys`) without UI flags. This grants ExoPlayer the complete physical dynamic range of the hardware DAC.
  - **Background Restoration (Steps → AudioManager)**: On exiting to the background (`onStop` / `onDestroy`), converts the internal software ratio back to the native system volume level, restores system media volume via `AudioManager`, and resets ExoPlayer software attenuation to `1.0f`. This guarantees seamless background playback and eliminates jarring loudness shifts when switching apps.
- 🎨 **Settings Screen & Sync Layout Shift Fix**:
  - **Title Normalization**: Standardized the Settings page `TopAppBar` title from "偏好設定" to "設定" (`settings_title`).
  - **Jitter-Free Sync Status**: Enforced single-line rendering (`maxLines = 1`, `softWrap = false`, `overflow = TextOverflow.Ellipsis`) inside a dedicated 24dp fixed-height container, completely eliminating layout shifting and vertical bouncing of the album name hierarchy settings during synchronization.

---

## 🚀 What's New in v0.5.1

- 📜 **Album Detail TopAppBar Single-Line Marquee (`basicMarquee`)**:
  - **Fixed Toolbar Height**: Constrained album title in `AlbumDetailScreen`'s `TopAppBar` to a single line (`maxLines = 1`, `softWrap = false`), preventing long album names from wrapping and vertically stretching the toolbar.
  - **Smooth Horizontal Marquee Scrolling**: Integrated Compose Foundation `Modifier.basicMarquee()` with an initial 2-second delay and repeat delay (`iterations = Int.MAX_VALUE`, `initialDelayMillis = 2000`, `repeatDelayMillis = 2000`, `velocity = 30.dp`).
  - **Auto-Activation**: Short album titles remain centered and static, while long titles smoothly scroll horizontally without clipping or overflowing.

---

## 🚀 What's New in v0.5

- 🌐 **Nextcloud Public Share Link Support (No Account Required)**:
  - Seamlessly browse and stream music libraries from Nextcloud folder public share links (`https://<domain>/s/<token>`) without requiring an account or app password.
  - Robust URL parser automatically normalizes link formats, including `/index.php/s/`, trailing slashes, custom port numbers, and subpaths.
  - Directly interacts with Nextcloud's public WebDAV endpoint (`https://<domain>/public.php/webdav/`) using HTTP Basic Authentication (`shareToken` as username; handles optional share passwords).
  - Clean `PrimaryTabRow` switcher on the login screen to toggle between "Account Login" and "Public Share Link", with clear status indicators and disconnect controls in Settings.
- 🔄 **WorkManager Foreground Service for Library Sync (`SyncLibraryWorker`)**:
  - Migrated recursive library scanning from ephemeral ViewModel coroutines to an AndroidX `WorkManager` background `CoroutineWorker`.
  - Promotes automatically to an active system Foreground Service (`foregroundServiceType="dataSync"`) with ongoing notification progress (`Syncing: [Folder Name] (X/Y)`).
  - Background-persistent execution ensures extensive music library syncs continue uninterrupted when the app is minimized or the screen is locked.
  - UI automatically reconnects and updates via `StateFlow` when the app is reopened.
- 🖼️ **Local Image Picker for Custom Album Art (Photo Picker)**:
  - Added a `[Select Image from Local Gallery]` option inside the album cover search bottom sheet.
  - Uses the modern Android Photo Picker (`ActivityResultContracts.PickVisualMedia`) without requiring external storage permissions.
  - Interactive preview dialog offering dual-mode application:
    - **Write to Cloud (WebDAV PUT)**: Uploads `cover.jpg` directly to the server to sync across all devices.
    - **Local Only**: Saves artwork to app-private storage, preserving the image locally without touching the server.
  - Instantly clears Coil image caches and refreshes album art across all views.
- 📱 **UI/UX Polish & Jitter Elimination**:
  - **Auto-Scrolling Search Input**: Long album titles in the cover search text field automatically pan horizontally to keep the cursor and ending text in view (`singleLine = true, maxLines = 1`).
  - **Overscroll Jitter Elimination**: Injected `NestedScrollConnection` into the bottom sheet to consume upward overscroll events when fully expanded, completely eliminating gesture jitter.
  - **Streamlined Home Screen**: Removed the static sync result banner between the search bar and album grid. Replaced it with a sleek, transient Material 3 `Snackbar` / `Toast` that auto-dismisses after 2~3 seconds, giving the album grid full vertical space.

---

## 🚀 What's New in v0.4

- ⚡ **High-Performance Incremental Diff Sync (`WebDavSyncRepository`)**:
  - Replaces slow, full recursive server rescans with intelligent differential synchronization, reducing sync times from minutes to seconds even for massive libraries (1,500+ albums / 15,000+ tracks).
  - **ETag & Modification Timestamp Caching**: Matches remote directory `d:getetag` and `d:getlastmodified` properties against cached local Room records. Unchanged album directories are skipped in milliseconds without issuing unnecessary child requests.
  - **Pruned Directory Cleanup (`deletedPaths`)**: Automatically detects folders removed or renamed on the remote server, batch-deletes obsolete `AlbumEntity` and `TrackEntity` records from Room, and purges corresponding local cover caches and offline audio files.
  - **Selective Deep Scan (`newPaths`)**: Recursively inspects only newly discovered or modified directories to parse audio metadata, track numbers, duration, and embedded/external cover art.
  - **Custom Cover Protection**: Strictly protects albums flagged with `isCustomLocalCover == true`, preventing custom local cover art from being overwritten or reverted during synchronization.
- ⚙️ **Dual Scanning Modes & Informative UI Feedback**:
  - **Quick Sync (Incremental Update)**: Default synchronization option in the settings menu; performs fast differential sync with real-time UI counters.
  - **Force Full Rescan (Full Rescan)**: Manual diagnostic option that clears the local database cache and reconstructs the library from scratch if metadata corruption occurs.
  - **Live Progress Messages**: Displays dynamic status updates ("Comparing directory differences...", "Cleaning deleted folders...", "Quick sync complete! Added X, updated Y, deleted Z...").
- 🗄️ **Room Database Migration (v3 → v4)**:
  - Added `etag` and `lastModified` columns to `albums` table with zero data loss migration (`MIGRATION_3_4`).
  - Added high-throughput batch deletion methods (`deleteAlbumsByIds`, `deleteAlbumsByPaths`, `deleteTracksByAlbumIds`) in `AlbumDao` and `TrackDao`.

---

## 🚀 What's New in v0.3

- 🖥️ **Adaptive Multi-Pane Layout for Tablets & Large Screens (Three-Pane Architecture)**:
  - Powered by `material3-window-size-class` (`WindowWidthSizeClass.Expanded` for widths ≥ 840dp).
  - Automatically switches between the standard mobile phone view (single pane with expandable bottom player sheet) and an expansive horizontal 3-pane workstation view on tablets, foldables, and landscape screens:
    - **Left Pane (Album List)**: Displays full album grid/list with draggable fast-scrollbar and selected album highlighting.
    - **Middle Pane (Track List)**: Instant track listing for the selected album with batch offline download, cover art search/customization, and live playing track indicators.
    - **Right Pane (Now Playing & Audio Specs)**: Permanent dedicated player featuring high-res album art, live stream telemetry (e.g., `FLAC • 96.0 kHz • 1024 kbps`), responsive playback controls, and buffer indicators.
  - Seamless state hoisting and shared ViewModel lifecycle ensure playback and queue state persist during device rotation and fold/unfold transitions.
- 📱 **Android 15 / Android 16 (API 35+) Edge-to-Edge System Insets Fix**:
  - Eliminated redundant top status bar padding causing blank gaps on Android 15 / 16 preview environments.
  - Ensures consistent visual alignment and edge-to-edge rendering across Android 8 through Android 16.
- 🎨 **Full-Bleed Adaptive App Icon & Launcher Border Fix**:
  - Replaced legacy solid slate/black adaptive icon background with a native vector linear gradient (`#22A2FC` to `#1852BC`, 270°).
  - Optimized foreground safe-zone scaling to 92%, removing top black edge gap artifacts on OEM squircle and custom launcher masks (MIUI, HyperOS, OneUI, Pixel).
- 🔄 **Player Lifecycle & Audio Focus Resilience**:
  - Strengthened `PlayerController` reconnect routines and audio session state restoration across activity recreations.

---

## 🚀 What's New in v0.2

- 🔍 **Online Album Cover Search**: Integrated iTunes Search API for quick album artwork lookups with up to 600x600 resolution previews and keyword refinement.
- ☁️ **Dual Cover Storage Modes**:
  - **Write to Cloud (Nextcloud WebDAV PUT)**: Uploads `cover.jpg` to the server and syncs the library across devices.
  - **Local Only**: Saves custom cover art to app-private storage without modifying remote cloud files.
- 🛡️ **Scan Conflict Protection**: Flagged with `isCustomLocalCover`, protecting personalized artwork from being overwritten during future library rescans.
- 📂 **Custom Offline Download Storage Location**:
  - Storage Access Framework (SAF) integration via `OpenDocumentTree`.
  - Save downloaded albums to public device directories (`/Music`) or external SD cards with persistable URI permissions.
  - Dual writing architecture supporting standard Java `File` and AndroidX `DocumentFile` streaming.
  - Offline tracks and embedded `cover.jpg` organized in dedicated album subfolders.

---

## ✨ Key Features

### 🔐 Seamless Nextcloud Authentication
- **Nextcloud Login Flow v2**: Log in easily via your mobile web browser with one click; tokens are generated securely.
- **Public Share Link Access (New in v0.5)**: Connect directly via public folder shares (`https://<domain>/s/<token>`) without requiring owner credentials; seamlessly streams and downloads with optional password support.
- **Direct App Passwords**: Support for manual server URL and dedicated app passwords.
- **QR Code Scanner**: Integrated CameraX and Google ML Kit barcode scanning for instant setup.
- **Hardware-Backed Security**: Session tokens and credentials are encrypted using `EncryptedSharedPreferences`.

### 🎵 High-Fidelity Audio & Streaming Engine
- **AndroidX Media3 (ExoPlayer)**: Native streaming supporting dynamic HTTP Range requests for instant seeking and minimal buffering latency.
- **Real-Time Audio Specs**: Displays live stream diagnostics in the player, including sample rate, bitrate, and format tag (e.g., `FLAC • 96.0 kHz • 1024 kbps`).
- **Comprehensive Format Support**: Seamless playback of Lossless FLAC, ALAC, WAV, MP3, AAC, OGG Vorbis, and Opus.

### 🔍 Cover Search & Gallery Customization (Enhanced in v0.5)
- **iTunes Search API Integration**: Discover candidate album covers with instant thumbnail grids and editable search keywords.
- **Local Photo Picker (New in v0.5)**: Select personal photos from your device's photo gallery via Android Photo Picker with zero storage permissions.
- **Write to Cloud vs. Local Only**: Choose between uploading `cover.jpg` directly to Nextcloud via WebDAV `PUT` or storing locally in app-private storage.
- **Rescan Protection**: Never lose custom covers during library syncs thanks to database-level protection flags (`isCustomLocalCover`).

### ⚡ Persistent Sync & Smart Background Tasks (New in v0.5)
- **WorkManager Foreground Sync**: Rock-solid background sync that persists when the app is minimized, providing real-time notification progress.
- **Transient UI Feedback**: Clean home layout with zero permanent banner clutter; completion notifications display via temporary auto-dismissing Snackbars.

### 📁 Smart Album Hierarchy & Path Mapping
- **Directory-Based Organization**: Intelligently aggregates your audio folders into albums.
- **Configurable Hierarchy Levels (1–5)**: Tailor how directory paths are mapped to album titles using custom separator depths (`-`). Handles nested multi-disc albums (`Artist - Year - Album - CD1`) gracefully.

### 🖼️ Multi-Tier Cover Art Fallback System
Ensures artwork is always displayed using a 4-tier resolution pipeline:
1. **Local Folder Cover**: Searches for images (`cover.jpg`, `folder.png`, `front.jpg`, etc.) directly inside the track's folder.
2. **Parent Directory Fallback**: Automatically checks parent directories for multi-disc structures (e.g., `CD1/` falling back to the parent album folder's artwork).
3. **Embedded ID3/Audio Metadata**: Extracts embedded pictures from the audio headers.
4. **Dynamic Vector Placeholder**: Gracefully falls back to stylish, theme-matched Material vector graphics.

### ⚡ Performance & Configurable LRU Disk Cache
- **Customizable Cache Bounds**: User-configurable audio cache buffer (128 MB to 2 GB) managed with an LRU (Least Recently Used) eviction policy.
- **Smooth Scrubbing**: Cached chunks ensure zero lag when skipping forward or rewinding tracks.

### 📥 Background Offline Mode & Custom Storage (New in v0.2)
- **Custom Download Location**: Save albums to internal app storage, public storage (`/Music`), or external SD cards via Storage Access Framework (SAF).
- **Persistable Permissions**: Retains folder access permissions across reboots using `takePersistableUriPermission`.
- **WorkManager Download Integration**: Foreground download service with real-time percentage notifications.
- **Seamless Offline Playback**: Direct playback from local files or SAF `content://` URIs without network access.

### 🎨 Modern Material 3 UI & Navigation
- **Jetpack Compose Architecture**: Fluid animations, dark/light theme support, and dynamic color adaptation.
- **Adaptive Three-Pane Layout (New in v0.3)**: Responsive multi-column layout for tablets, foldables, and landscape orientations with real-time state synchronization.
- **Fast-Scroll Alphabet Indicator**: Drag thumb with an interactive alphabet bubble for rapid browsing across extensive music libraries.
- **Bottom Sliding Player Sheet**: Expandable now-playing sheet with playback queue management, shuffle, repeat, and volume control on phones.
- **Full-Bleed Adaptive App Icon (New in v0.3)**: High-resolution adaptive icon with seamless linear gradient background across all Android launcher shapes.
- **System Integration**: Background `MediaSessionService` supporting lock screen controls, notification actions, Bluetooth AVRCP metadata sync, and auto-pause on headphone disconnection.

---

## 🛠️ Tech Stack & Architecture

| Layer | Technologies |
|---|---|
| **UI & Presentation** | Jetpack Compose, Material 3, Material 3 WindowSizeClass, Navigation Compose |
| **Architecture Pattern** | MVVM + Clean Architecture, Kotlin Coroutines, StateFlow / SharedFlow |
| **Audio Engine** | AndroidX Media3 (ExoPlayer), MediaSessionService, MediaNotificationManager |
| **Networking & Protocols** | OkHttp 4, WebDAV (`PROPFIND`, `GET`, `PUT`, `HEAD`), Nextcloud Login Flow v2 |
| **Local Persistence** | AndroidX Room (SQLite ORM) for library metadata & offline status; Jetpack DataStore |
| **Storage & I/O** | Android Storage Access Framework (SAF), AndroidX DocumentFile, Scoped Storage |
| **Security** | AndroidX Security Crypto (`EncryptedSharedPreferences`, MasterKeys) |
| **Image Loading** | Coil 2.7 (Compose integration with WebDAV authenticated headers & memory/disk cache management) |
| **Background Tasks** | AndroidX WorkManager with Foreground Service support |
| **Vision & Scanning** | AndroidX CameraX, Google ML Kit Barcode Scanning |

---

## 🚀 Getting Started

### Prerequisites
- An Android device running **Android 8.0 (Oreo / API Level 26)** or higher (fully tested on Android 14, 15, and 16).
- A running **Nextcloud instance** (version 20+ recommended) with WebDAV access enabled.
- A music folder stored on your Nextcloud instance (e.g., `Music/`).

### Download
You can download the pre-compiled, signed APK directly from GitHub Releases:
- 👉 **[Download NextcloudPlayer-v0.6.1.apk](https://github.com/maria0010101/nextcloud-music-player-android/releases/latest)**

---

## 🔨 Building from Source

To compile and build the application yourself:

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/maria0010101/nextcloud-music-player-android.git
   cd nextcloud-music-player-android
   ```

2. **Environment Requirements**:
   - JDK 17 or higher
   - Android SDK (API 36 build tools)

3. **Build Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The generated APK will be available at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. **Build Release APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   The signed release APK will be available at:
   `app/build/outputs/apk/release/app-release.apk`

---

## 📱 Quick Usage Guide

1. **Sign In**: Launch the app, enter your Nextcloud server URL (e.g., `https://cloud.example.com`), and authenticate through your browser or with an App Password.
2. **Select Music Directory**: Choose the root folder where your audio files are stored.
3. **Sync Library**: Tap the sync button to scan folders via WebDAV. The app will organize tracks into albums and fetch cover art.
4. **Customize Hierarchy**: In Settings, adjust the Album Hierarchy Depth (1–5 levels) to fit your preferred directory naming conventions.
5. **Search & Update Covers**: In Album Details, tap "線上搜尋/更換封面" to search iTunes for high-res artwork, then choose "寫回雲端" (Sync to Nextcloud) or "僅本機顯示" (Local Only).
6. **Set Download Path & Offline Listening**: In Settings, choose your preferred download directory (SAF public storage or app internal). Tap "下載整張專輯" to download for offline listening.

---

## 🗺️ Roadmap

- [ ] Custom Playlist creation and management
- [ ] Synchronized Lyrics (`.lrc` files and embedded USLT tags)
- [x] Built-in Equalizer (10-Band Graphic EQ), AutoEq headphone sound profiles, and Channel balance
- [ ] Android Auto integration
- [ ] Sleep timer feature
- [ ] Scrobbling support (ListenBrainz / Last.fm)

---

## 💖 Acknowledgements & Credits

We extend our sincere gratitude to the following outstanding open-source projects and authors whose pioneering work made these audio capabilities possible:

- **[nulldio/32steps](https://github.com/nulldio/32steps)** by [@nulldio](https://github.com/nulldio):
  - Invaluable design and implementation reference for the interactive 10-band graphic equalizer response curve view (`GraphicEqView`), gesture ergonomics (direct drag & double-tap to zero), vertical gain bars, AutoEq integration architecture, and burst-free continuous volume step mapping (`VolumeStepManager`).
- **[jaakkopasanen/AutoEq](https://github.com/jaakkopasanen/AutoEq)** by Jaakko Pasanen:
  - The premier open-source database of 6,000+ calibrated headphone frequency response curves and target compensation profiles.
- **[AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)**:
  - Robust high-fidelity client-side audio streaming and decoding engine.

---

## ⚠️ Disclaimer

This is an independent open-source project created by community contributors. It is **not** officially affiliated with, maintained by, or endorsed by Nextcloud GmbH. "Nextcloud" is a registered trademark of Nextcloud GmbH.

---

## 📄 License

This project is open-source software licensed under the [MIT License](LICENSE).
