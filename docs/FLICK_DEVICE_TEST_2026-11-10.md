# Flick integration device test — 2026-11-10

Build: `0.9.0-flick-dev` / versionCode 14, arm64 debug, branch
`feature/flick-rust-audio`. Device: Xiaomi 2109119DG (`lisa`), Android 14/API34.
Flick source baseline: 0.23.1-beta.3 / f35d87980e8a8892764668966d49a11b2f10c00e.

## Completed

- Locked Rust library suite: **330 passed**, zero failures.
- Android/Kotlin JVM suite: **22 passed**, zero failures.
- Debug APK and instrumentation APK built successfully with SDK36 / NDK28.2.
- Native library LOAD alignment checked: 16 KB.
- Existing signing certificate matched; used `adb install -r`, never uninstall or
  clear app data. Installed version14 confirmed.
- Device instrumentation: **3 passed**, zero failures:
  1. System→Rust→system MediaSession handoff, prepared-paused native source,
     play/pause/seek, gain/off transitions, strict no-DAC visible failure without
     speaker fallback.
  2. Real indexed Nextcloud source via existing authentication/cache, Rust decode,
     muted playback and Range seek to30 seconds followed by advancing playback.
  3. Actual injected foreground volume keys: off changes Android media volume;
     Flick precision changes app fraction without changing system volume; strict
     changes neither. Preferences and system volume restored in finally blocks.
- Main DB schema remains6, Room identity unchanged. Before/after normalized rows
  of albums, tracks, playlists and playlist_tracks match exactly. Album count1491,
  track count15948. Backup retained locally before first development launch.
- No playback output was deliberately made audible: fixtures were silent and
  network playback gain was zero before Resume. Key-test stream level restored.

The first separate test-package install required the phone's normal USB install
prompt. An initial Activity-launch test hit MIUI background/idle restrictions;
its harness was changed to shell-authorized launch plus lifecycle observation.
Final instrumentation passed all three tests. Neither issue required changing
phone security settings.

## Not hardware-verified

No USB DAC was connected during this session. Successful direct USB negotiation,
DAC-specific hardware volume behavior, long-duration USB stability and captured
bit-exact output still require the actual DAC. No analog audio-quality claim is
made. Strict mode currently supports declared PCM16/24 only; PCM32/DSD/unknown
precision are rejected rather than silently degraded. Other Android versions
and ABIs were not device-tested. Rust EQ/AutoEQ UI is not exposed; original
Android effects remain available on the standard backend.

APK SHA-256:
`2cfd774d4670d998ad55708ea42b89d643fa31340ee294b8db6577fb1c579eb7`

Local build logs, instrumentation output, APK and protected device backups are
under the operator's workspace artifacts directory; backups must not be added
to Git or published.
