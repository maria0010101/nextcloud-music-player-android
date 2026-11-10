# Flick Rust audio backend

The development build adds an optional Flick backend alongside Android/Media3
standard playback. The existing Nextcloud account, Room schema v6, playlists,
artwork and indexed Media3 cache are retained. The native engine is pinned to
Flick 0.23.1-beta.3; source and native build details are in `native/flick/README.md`.

## Modes

Settings → 音訊播放引擎 selects 原生系統音效控制 or Flick Rust audio engine.
Changing engines pauses first, copies the queue/index/position, and prepares the
selected backend. Press Play explicitly to resume.

Volume precision modes are:

- 關閉音量調節精度: app gain is unity and physical volume keys use Android's
  media volume. Old 15- and 30-step preferences normalize to this mode.
- 25 / 50 / 100 steps: the app's existing perceptual gain is used by either
  backend. The Rust boundary accepts the final linear gain, not a second curve.
- bit-perfect: only available with Flick. App gain is unity, DSP is bypassed,
  physical volume keys are consumed while the Activity is foreground, and audio
  uses an authorized compatible USB DAC directly rather than Android's mixer.
  Control the external device appropriately. The app does not globally disable
  Android system UI volume controls or raise the global stream volume.

The original fine-volume fraction is retained across modes. Leaving strict mode
or moving fine volume to unity pauses first. Standard Android effects remain
saved but are not applied/edited while Flick is selected. Rust DSP controls beyond
gain are not exposed in this integration.

## Strict-mode limits

Only declared PCM16/24 sources with matching output rate, precision and channels
are admitted. Unknown precision, PCM32, DSD, fallback routes, non-unity gain,
resampling, changed clock/route or non-passthrough processing fail visibly. No
silent switch to speakers or system resampling is allowed. A USB permission
request is a normal Android per-device authorization, not a Root request.

PCM16/24 round-trips are tested, but no UI label or clock readback proves an
arbitrary DAC's digital/analog output. Hardware bit-exact measurement remains a
separate acceptance requirement. Regular Flick output uses Android native audio
and may follow the system output sample rate; do not call it strict bit-perfect.

## Data/control architecture

MediaSession owns one active Player. ExoPlayer remains the standard backend;
FlickPlayer adapts the real Rust decoder/engine to Media3 1.5.1. Native operations
are serialized across Player/service instances. Android focus loss/duck requests,
becoming noisy and strict USB detach pause/stop rather than corrupt strict PCM.

FlickDataSourceServer binds an ephemeral port to 127.0.0.1 only. Random private
URI tokens map to the existing CacheDataSource, serving bounded Range reads to
Rust. This retains authenticated OkHttp, cache span/index management, local files
and content URIs without treating cache fragments as complete audio files.
Original URLs and credentials are not embedded in the loopback token. Shutdown
closes the bridge only after serialized native teardown.

## Build and test

This phone build includes arm64-v8a only. Gradle's preBuild compiles native Rust
and packages the matching C++ runtime and dependency notices. Install the Rust
Android target and NDK first; set ANDROID_NDK_HOME if not using the documented
SDK default. Generated `.so` and Cargo target output are ignored by Git.

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
cargo test --locked --manifest-path native/flick/Cargo.toml --lib
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.nextcloud.musicplayer.playback.FlickPlaybackTest com.nextcloud.musicplayer.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation uses silent fixtures, preserves audio preferences, and does not
clear the user's library. The authenticated-stream test uses one existing remote
library URL and mutes app gain before playing; it can populate the normal cache.
Do not uninstall the app or use destructive database fallback for testing.

Test results for the development session are recorded separately; tests without a
USB DAC do not establish real USB hardware compatibility or bit-exact output.
