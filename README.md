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
- **Direct App Passwords**: Support for manual server URL and dedicated app passwords.
- **QR Code Scanner**: Integrated CameraX and Google ML Kit barcode scanning for instant setup.
- **Hardware-Backed Security**: Session tokens and credentials are encrypted using `EncryptedSharedPreferences`.

### 🎵 High-Fidelity Audio & Streaming Engine
- **AndroidX Media3 (ExoPlayer)**: Native streaming supporting dynamic HTTP Range requests for instant seeking and minimal buffering latency.
- **Real-Time Audio Specs**: Displays live stream diagnostics in the player, including sample rate, bitrate, and format tag (e.g., `FLAC • 96.0 kHz • 1024 kbps`).
- **Comprehensive Format Support**: Seamless playback of Lossless FLAC, ALAC, WAV, MP3, AAC, OGG Vorbis, and Opus.

### 🔍 Online Cover Search & Dual Storage (New in v0.2)
- **iTunes Search API Integration**: Discover candidate album covers with instant thumbnail grids and editable search keywords.
- **Write to Cloud vs. Local Only**: Choose between uploading `cover.jpg` directly to Nextcloud via WebDAV `PUT` or storing locally in app-private storage.
- **Rescan Protection**: Never lose custom covers during library syncs thanks to database-level protection flags (`isCustomLocalCover`).

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
- 👉 **[Download NextcloudPlayer-v0.3.apk](https://github.com/maria0010101/nextcloud-music-player-android/releases/latest)**

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
- [ ] Built-in Equalizer (EQ) and audio effects
- [ ] Android Auto integration
- [ ] Sleep timer feature
- [ ] Scrobbling support (ListenBrainz / Last.fm)

---

## ⚠️ Disclaimer

This is an independent open-source project created by community contributors. It is **not** officially affiliated with, maintained by, or endorsed by Nextcloud GmbH. "Nextcloud" is a registered trademark of Nextcloud GmbH.

---

## 📄 License

This project is open-source software licensed under the [MIT License](LICENSE).
