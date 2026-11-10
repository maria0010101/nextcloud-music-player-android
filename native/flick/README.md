# Flick native audio integration

Pinned upstream: https://github.com/moss-apps/Flick/tree/f35d87980e8a8892764668966d49a11b2f10c00e
(version 0.23.1-beta.3). Upstream engine/audio and UAC2 sources are retained with
MIT attribution in LICENSE; vendored CPAL, Opus and WavPack carry their notices.
Cargo.lock pins registry dependencies. `licenses/` records dependency licenses.
libusb is LGPL; its notice and all application/native source plus build scripts
are provided to permit modification and rebuilding/relinking. Symphonia is MPL;
modified files, if any, must retain the applicable license.

## Intentional integration changes

- Flutter bridge generation/runtime, library scanning, SMB and Flutter UI are not included.
- A small Kotlin/JNI surface uses JSON control/result messages, with panic boundaries.
- The PCM decoder, ring buffers, real-time engine and output transport are from Flick,
  not a substitute Kotlin gain effect. Regular playback uses the native Android
  output; strict mode uses UAC2 USB direct and disallows output fallback.
- Kotlin supplies **linear** gain. `AudioCallbackData::get_gain` no longer applies
  Flick's second perceptual volume curve. Strict mode requires unity and rejects gain changes.
- Native `stop` is idempotent before first open and after repeated switches; it shuts down
  output workers while preserving registered USB ownership until `clearUsb`.
- Native `open` returns prepared/paused. The callback is paused before source enqueue
  and remains so until explicit `resume`, including after seek while paused.
- PCM float-to-integer normalization uses 2^31 (with rounding and saturation), not
  2^31-1. This preserves signed PCM16/24 samples through power-of-two normalization.
- Strict playback only admits declared PCM16/24 precision, exact source rate and
  channel count, a verified USB clock, and the `usb_direct` strategy. Unknown or
  PCM32 precision is rejected because float32 cannot preserve all PCM32 values.
- Snapshot rechecks direct USB stream/clock/format state; failure pauses playback.
  No claim is made that statistical upstream payload guards are a bit-exact test.
- DSD, pitch shifting, convolution UI and other extended controls are not exposed by JNI.
  SoundTouch is excluded from Cargo dependencies and compilation; pitch_shifter is
  a disabled integration adapter. Bundled upstream source is retained for attribution.

## Build

Install Rust target `aarch64-linux-android`, Android NDK (tested 28.2.13676358),
clang/libclang for bindgen, and normal Android SDK/JDK tooling. Run:

```sh
ANDROID_NDK_HOME=/path/to/ndk ./native/flick/build-android.sh
```

This builds `nextcloud_flick` and copies its `.so` plus the matching NDK C++ runtime
to `app/src/main/jniLibs/arm64-v8a`. It does not require Flutter or cargo-ndk.
The linker sets a 16 KiB maximum page size. Other ABIs must be built explicitly
before being distributed; the present device build is arm64 only.

To rebuild/relink against modified LGPL libusb, modify its source from the locked
`libusb1-sys` dependency (or vendor/patch that crate in Cargo.toml), then rebuild this
open-source application. No signing keys or unavailable proprietary objects are
required for the debug/development build.

## Tests

```sh
cargo test --manifest-path native/flick/Cargo.toml --lib pcm::tests --no-default-features
cargo test --manifest-path native/flick/Cargo.toml --lib native_prepare_paused --no-default-features
```

Tests exercise exhaustive PCM16 round-trips, 100,000 PCM24 vectors and boundaries,
clipping, and source preparation without pre-resume output. USB hardware behavior
must additionally be validated on the actual phone and DAC; software unit tests do
not prove the DAC's internal processing or analog output.
