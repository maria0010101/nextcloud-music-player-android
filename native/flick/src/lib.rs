pub mod api;

// Audio engine is now available on all platforms including Android (using CPAL with Oboe backend)
pub mod audio;
pub mod pcm;
#[cfg(target_os = "android")]
mod jni_bridge;

/// Custom UAC 2.0 USB Audio (DAC/AMP detection and direct playback paths).
/// Real implementation is gated by the `uac2` feature.
pub mod uac2;



#[cfg(target_os = "android")]
use std::{ffi::c_void, sync::OnceLock};

#[cfg(target_os = "android")]
use jni::{
    objects::{Global, JObject, JString},
    sys::{jboolean, jdouble, jint, jstring},
    JNIEnv,
};

#[cfg(target_os = "android")]
static ANDROID_APP_CONTEXT: OnceLock<Global<JObject<'static>>> = OnceLock::new();

#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicBool, Ordering};

#[cfg(target_os = "android")]
static DEVELOPER_MODE: AtomicBool = AtomicBool::new(false);
#[cfg(not(target_os = "android"))]
use std::sync::atomic::AtomicBool;
#[cfg(not(target_os = "android"))]
static DEVELOPER_MODE: AtomicBool = AtomicBool::new(true);

#[macro_export]
macro_rules! dev_eprintln {
    ($($arg:tt)*) => {
        if crate::DEVELOPER_MODE.load(std::sync::atomic::Ordering::Relaxed) {
            let __msg = format!($($arg)*);
            eprintln!("{}", __msg);
            // ponytail: route through android_logger (tag "RustUSB") so devLogs
            // reach `adb logcat`. Raw eprintln/stderr is invisible on Android.
            log::info!("{}", __msg);
            crate::api::logging::forward_to_sink(__msg);
        }
    };
}

#[cfg(target_os = "android")]
fn initialize_android_app_context<'local>(
    env: &mut jni::Env<'local>,
    context: &JObject<'local>,
) -> Result<(), String> {
    if ANDROID_APP_CONTEXT.get().is_some() {
        return Ok(());
    }

    let java_vm = env.get_java_vm().map_err(|error| error.to_string())?;
    let global_context = env
        .new_global_ref(context)
        .map_err(|error| error.to_string())?;
    let context_ptr = global_context.as_obj().as_raw() as *mut c_void;

    match ANDROID_APP_CONTEXT.set(global_context) {
        Ok(()) => {
            unsafe {
                ndk_context::initialize_android_context(
                    java_vm.get_raw() as *mut c_void,
                    context_ptr,
                );
            }
            Ok(())
        }
        Err(_) => Ok(()),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jni::sys::jint {
    android_logger::init_once(
        android_logger::Config::default()
            // Debug builds log Debug+; release keeps Info so DSD transport
            // telemetry (starvation warns, SAS probe) reaches logcat.
            .with_max_level(if cfg!(debug_assertions) {
                log::LevelFilter::Debug
            } else {
                log::LevelFilter::Info
            })
            .with_tag("RustUSB"),
    );
    jni::JNIVersion::V1_6.into()
}

/// FRB installs its own `android_logger` during `RustLib.init` and clobbers
/// the max level, silencing our logs after boot. Call at audio API entry
/// points to keep debug builds logging for the whole session.
#[cfg(target_os = "android")]
pub fn assert_android_debug_logging() {
    if cfg!(debug_assertions) {
        log::set_max_level(log::LevelFilter::Debug);
    }
}

#[cfg(not(target_os = "android"))]
pub fn assert_android_debug_logging() {}

