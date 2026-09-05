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
- **Direct WebDAV Interaction**: Utilizes native Nextcloud WebDAV endpoints (`PROPFIND`, HTTP range `GET`) for folder discovery and progressive streaming.
- **Client-Side Decoding**: Powered by AndroidX Media3 (ExoPlayer), decoding FLAC, MP3, AAC, OGG, WAV, ALAC, and Opus right on your phone.
- **Smart Metadata & Image Resolution**: Dynamically discovers album structures and cover art without requiring server modifications.
- **Low Overhead**: Enjoy your private lossless music collection anywhere without heating up your home server or NAS.

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

### 📥 Background Offline Mode
- **WorkManager Download Integration**: Download entire albums or individual tracks for offline listening while traveling or in low-connectivity environments.
- **Foreground Service Transparency**: Persistent background progress notifications with full progress feedback.

### 🎨 Modern Material 3 UI & Navigation
- **Jetpack Compose Architecture**: Fluid animations, dark/light theme support, and dynamic color adaptation.
- **Fast-Scroll Alphabet Indicator**: Drag thumb with an interactive alphabet bubble for rapid browsing across extensive music libraries.
- **Bottom Sliding Player Sheet**: Expandable now-playing sheet with playback queue management, shuffle, repeat, and volume control.
- **System Integration**: Background `MediaSessionService` supporting lock screen controls, notification actions, Bluetooth AVRCP metadata sync, and auto-pause on headphone disconnection.

---

## 🛠️ Tech Stack & Architecture

| Layer | Technologies |
|---|---|
| **UI & Presentation** | Jetpack Compose, Material 3, Navigation Compose, Compose ViewModels |
| **Architecture Pattern** | MVVM + Clean Architecture, Kotlin Coroutines, StateFlow / SharedFlow |
| **Audio Engine** | AndroidX Media3 (ExoPlayer), MediaSessionService, MediaNotificationManager |
| **Networking & Protocols** | OkHttp 4, WebDAV (`PROPFIND`, `GET`, `HEAD`), Nextcloud Login Flow v2 |
| **Local Persistence** | AndroidX Room (SQLite ORM) for library metadata & offline status; Jetpack DataStore |
| **Security** | AndroidX Security Crypto (`EncryptedSharedPreferences`, MasterKeys) |
| **Image Loading** | Coil 3 (Compose integration with WebDAV authenticated headers) |
| **Background Tasks** | AndroidX WorkManager with Foreground Service support |
| **Vision & Scanning** | AndroidX CameraX, Google ML Kit Barcode Scanning |

---

## 🚀 Getting Started

### Prerequisites
- An Android device running **Android 8.0 (Oreo / API Level 26)** or higher.
- A running **Nextcloud instance** (version 20+ recommended) with WebDAV access enabled.
- A music folder stored on your Nextcloud instance (e.g., `Music/`).

### Download
You can download the pre-compiled, signed APK directly from GitHub Releases:
- 👉 **[Download NextcloudPlayer-v0.1.apk](https://github.com/maria0010101/nextcloud-music-player-android/releases/latest)**

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
5. **Stream & Download**: Tap any track or album to stream immediately. Long-press or tap the download button to save music locally for offline playback.

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
