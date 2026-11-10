//! Small JNI control surface. Flutter and its runtime are not linked.
use crate::api::audio_api as api;
use crate::audio::{
    decoder::{probe_file, probe_http, DecoderThread},
    decoder_handle::DecoderHandle,
    source::PlaybackRange,
};
use jni::{
    objects::{JObject, JString},
    sys::{jboolean, jdouble, jfloat, jint, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
};
static STRICT: AtomicBool = AtomicBool::new(false);
static META: Mutex<Option<Value>> = Mutex::new(None);
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
static ENDED: AtomicBool = AtomicBool::new(false);

fn guard(f: impl FnOnce() -> Result<Value, String>) -> Value {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            let _ = api::audio_pause();
            *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = Some(e.clone());
            json!({"error":e})
        }
        Err(_) => {
            let _ = api::audio_pause();
            *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) =
                Some("Rust audio engine operation failed".into());
            json!({"error":"Rust audio engine operation failed"})
        }
    }
}
fn reply(env: &mut JNIEnv<'_>, f: impl FnOnce() -> Result<Value, String>) -> jstring {
    let payload = guard(f).to_string();
    env.with_env(|env| -> jni::errors::Result<jstring> { Ok(env.new_string(payload)?.into_raw()) })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
fn string(env: &mut jni::Env<'_>, s: &JString<'_>) -> Result<String, String> {
    s.try_to_string(env)
        .map_err(|_| "Invalid JNI string".into())
}
fn decode_string(env: &mut JNIEnv<'_>, s: JString<'_>) -> Result<String, String> {
    let value = env
        .with_env(|e| -> jni::errors::Result<String> { Ok(string(e, &s).unwrap_or_default()) })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
    Ok(value)
}
fn void(r: Result<(), String>) -> Result<Value, String> {
    r.map(|_| json!({}))
}
fn strict_check() -> Result<(), String> {
    if !STRICT.load(Ordering::Acquire) {
        return Ok(());
    }
    if api::read_audio_engine(|h| h.is_passthrough() && h.get_playback_speed() == 1.0) != Some(true)
    {
        return Err("DSP or playback speed changed during strict playback".into());
    }
    let d = crate::uac2::android_direct_debug_state();
    if !d.registered || !d.stream_active || !d.clock_verification_passed || d.resampling_active {
        return Err("bit-perfect USB output unavailable or no longer matches the source".into());
    }
    let meta = META.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(m) = meta.as_ref() {
        if d.playback_format_sample_rate != m["sampleRate"].as_u64().map(|v| v as u32)
            || d.playback_format_bit_depth != m["bitDepth"].as_u64().map(|v| v as u8)
            || d.playback_format_channels != m["channels"].as_u64().map(|v| v as u16)
        {
            return Err("USB format changed during strict playback".into());
        }
    }
    Ok(())
}
fn open(uri: String, headers: String, strict: bool, gain: f32) -> Result<Value, String> {
    // Tear down a previous source before preparing another format. Never let source samples
    // reach an unverified fallback output, even for a single callback.
    let _ = api::audio_shutdown();
    STRICT.store(false, Ordering::Release);
    *META.lock().unwrap_or_else(|p| p.into_inner()) = None;
    *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = None;
    ENDED.store(false, Ordering::Release);
    let headers: HashMap<String, String> =
        serde_json::from_str(&headers).map_err(|_| "Invalid HTTP headers")?;
    let probe = if uri.starts_with("https://") || uri.starts_with("http://") {
        probe_http(&uri, headers)
    } else {
        probe_file(Path::new(&uri))
    }
    .map_err(|_| "Cannot decode this audio source")?;
    let sr = probe.source_info.original_sample_rate;
    let channels = probe.source_info.channels;
    let bit_depth = probe
        .format
        .tracks()
        .iter()
        .find(|t| t.id == probe.track_id)
        .and_then(|t| t.codec_params.as_ref())
        .and_then(|p| p.audio())
        .and_then(|p| p.bits_per_sample)
        .unwrap_or(0);
    let duration = (probe.source_info.duration_secs * 1000.0) as u64;
    if strict {
        crate::pcm::require_strict_precision(bit_depth)?;
    }

    api::audio_set_high_res_mode(!strict);
    api::audio_set_dap_bit_perfect_enabled(strict);
    crate::uac2::set_android_direct_usb_enabled(strict);
    if strict {
        if !crate::uac2::android_direct_debug_state().registered {
            return Err(
                "Connect and authorize a compatible USB DAC before bit-perfect playback".into(),
            );
        }
        crate::uac2::set_android_usb_playback_format(Some(
            crate::uac2::AndroidDirectUsbPlaybackFormat {
                sample_rate: sr,
                bit_depth: bit_depth as u8,
                channels: channels as u16,
                is_dop: false,
                dsd_transport: crate::uac2::DsdTransportMode::None,
                dsd_bit_rate: 0,
            },
        ))?;
    }
    api::audio_set_volume(if strict { 1.0 } else { gain.clamp(0.0, 1.0) })?;
    api::audio_prepare_engine(Some(sr))?;
    let runtime = api::audio_get_runtime_debug_json_state();
    let out_rate = api::audio_get_sample_rate().ok_or("No output rate")?;
    let out_ch = api::audio_get_channels().ok_or("No output channels")?;
    let strategy = runtime
        .output_strategy
        .clone()
        .unwrap_or_else(|| "unknown".into());
    let meta = json!({"durationMs":duration,"sampleRate":sr,"channels":channels,"bitDepth":bit_depth,"strategy":strategy,"outputSampleRate":out_rate,"outputChannels":out_ch});
    *META.lock().unwrap_or_else(|p| p.into_inner()) = Some(meta.clone());
    STRICT.store(strict, Ordering::Release);
    if strict {
        if strategy != "usb_direct"
            || out_rate != sr
            || out_ch != channels
            || runtime.resampler_active == Some(true)
        {
            let _ = api::audio_shutdown();
            return Err(
                "The DAC cannot provide an exact USB direct format; fallback is blocked".into(),
            );
        }
        strict_check()?;
    }
    api::audio_set_pipeline_mode_passthrough(strict)?;
    api::audio_set_replaygain(0.0)?;
    api::audio_set_crossfade(false, 0.0)?;
    api::audio_set_equalizer(false, 0.0, vec![])?;
    api::audio_set_playback_speed(1.0)?;
    let (source, thread) = DecoderThread::spawn_from_probe_result(
        probe,
        out_rate,
        out_ch,
        None,
        PlaybackRange::default(),
    )
    .map_err(|_| "Audio decoder could not start")?;
    api::with_audio_engine(|h| {
        h.set_dop_override(false)?;
        h.prepare_paused(source, DecoderHandle::Symphonia(thread))
    })?;
    Ok(meta)
}
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_initEngine<'a>(
    mut env: JNIEnv<'a>,
    _: JObject<'_>,
    context: JObject<'a>,
) -> jstring {
    let init = env
        .with_env(|env| -> jni::errors::Result<bool> {
            Ok(crate::initialize_android_app_context(env, &context).is_ok())
        })
        .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
    reply(&mut env, || {
        if !init {
            return Err("Could not initialize Android audio context".into());
        }
        api::audio_init()?;
        Ok(json!({}))
    })
}
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_open(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    uri: JString<'_>,
    headers: JString<'_>,
    strict: jboolean,
    gain: jfloat,
) -> jstring {
    let u = decode_string(&mut env, uri);
    let h = decode_string(&mut env, headers);
    reply(&mut env, || open(u?, h?, strict, gain))
}
macro_rules! simple {
    ($name:ident,$body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv<'_>, _: JObject<'_>) -> jstring {
            reply(&mut env, || $body)
        }
    };
}
simple!(
    Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_pause,
    void(api::audio_pause())
);
simple!(
    Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_resume,
    {
        strict_check()?;
        void(api::audio_resume())
    }
);
simple!(
    Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_stop,
    {
        // Stop is a lifecycle operation, including before first open and after
        // repeated backend switches. Shutdown is idempotent and releases output
        // threads/handles without clearing the registered USB device/duplicated FD.
        ENDED.store(false, Ordering::Release);
        STRICT.store(false, Ordering::Release);
        *META.lock().unwrap_or_else(|p| p.into_inner()) = None;
        *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = None;
        void(api::audio_shutdown())
    }
);
simple!(
    Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_release,
    {
        STRICT.store(false, Ordering::Release);
        void(api::audio_shutdown())
    }
);
simple!(
    Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_clearUsb,
    {
        let _ = api::audio_shutdown();
        crate::uac2::clear_android_usb_device();
        Ok(json!({}))
    }
);
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_seek(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    seconds: jdouble,
) -> jstring {
    reply(&mut env, || {
        strict_check()?;
        ENDED.store(false, Ordering::Release);
        void(api::audio_seek(seconds.max(0.0)))
    })
}
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_setGain(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    gain: jfloat,
) -> jstring {
    reply(&mut env, || {
        if STRICT.load(Ordering::Acquire) && gain != 1.0 {
            return Err("Volume adjustment disabled in bit-perfect mode".into());
        }
        void(api::audio_set_volume(gain.clamp(0.0, 1.0)))
    })
}
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_snapshot(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
) -> jstring {
    reply(&mut env, || {
        let mut error = None;
        for _ in 0..256 {
            match api::audio_poll_event() {
                Some(api::AudioEventType::TrackEnded { .. }) => {
                    ENDED.store(true, Ordering::Release);
                }
                Some(api::AudioEventType::Error { message }) => error = Some(message),
                None => break,
                _ => {}
            }
        }
        if let Err(e) = strict_check() {
            let _ = api::audio_pause();
            error = Some(e)
        }
        if let Some(e) = error {
            *LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()) = Some(e);
        }
        let mut v = META
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone()
            .unwrap_or(json!({}));
        let progress = api::audio_get_progress();
        v["positionMs"] = json!(progress
            .as_ref()
            .map(|p| (p.position_secs * 1000.0) as u64)
            .unwrap_or(0));
        let last = LAST_ERROR.lock().unwrap_or_else(|p| p.into_inner()).clone();
        v["state"] = json!(if last.is_some() {
            "error".into()
        } else if ENDED.load(Ordering::Acquire) {
            "ended".into()
        } else {
            api::audio_get_state()
        });
        if let Some(e) = last {
            v["error"] = json!(e)
        }
        Ok(v)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_nextcloud_musicplayer_audio_flick_NativeFlick_registerUsb(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    fd: jint,
    vid: jint,
    pid: jint,
    product: JString<'_>,
    manufacturer: JString<'_>,
    serial: JString<'_>,
    device: JString<'_>,
) -> jstring {
    let p = decode_string(&mut env, product);
    let m = decode_string(&mut env, manufacturer);
    let s = decode_string(&mut env, serial);
    let d = decode_string(&mut env, device);
    reply(&mut env, || {
        let serial = s?;
        let device = d?;
        let usb = crate::uac2::AndroidDirectUsbDevice::try_new(
            fd,
            vid as u16,
            pid as u16,
            p?,
            m?,
            if serial.is_empty() {
                None
            } else {
                Some(serial)
            },
            Some(device),
        )?;
        void(crate::uac2::register_android_usb_device(usb))
    })
}
