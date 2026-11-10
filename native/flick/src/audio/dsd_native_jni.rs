use std::sync::atomic::{AtomicBool, Ordering};

#[cfg(target_os = "android")]
use std::sync::atomic::AtomicPtr;

#[cfg(target_os = "android")]
use jni::{jni_sig, jni_str, strings::JNIString};

static DSD_ENCODING_AVAILABLE: AtomicBool = AtomicBool::new(false);

#[cfg(target_os = "android")]
static DSD_TRACK_CLASS: AtomicPtr<std::ffi::c_void> = AtomicPtr::new(std::ptr::null_mut());

#[cfg(target_os = "android")]
const DSD_CLASS_NAME: &str = "com/mossapps/flick/DsdAudioTrackManager$Companion";

#[cfg(target_os = "android")]
fn get_cached_class() -> Option<*mut std::ffi::c_void> {
    let ptr = DSD_TRACK_CLASS.load(Ordering::Acquire);
    if ptr.is_null() {
        None
    } else {
        Some(ptr)
    }
}

#[cfg(target_os = "android")]
fn find_dsd_class<'env>(
    env: &mut jni::Env<'env>,
) -> Result<jni::objects::JClass<'env>, jni::errors::Error> {
    if let Some(ptr) = get_cached_class() {
        return Ok(unsafe { jni::objects::JClass::from_raw(env, ptr as jni::sys::jclass) });
    }

    match env.find_class(JNIString::new(DSD_CLASS_NAME)) {
        Ok(class) => {
            cache_class(env, &class);
            Ok(class)
        }
        Err(_) => {
            let _ = env.exception_clear();
            let ctx = ndk_context::android_context();
            let context =
                unsafe { jni::objects::JObject::from_raw(env, ctx.context() as jni::sys::jobject) };
            if context.is_null() {
                return Err(jni::errors::Error::NullPtr(
                    "No Android context available for classloader lookup",
                ));
            }
            let loader = env
                .call_method(
                    &context,
                    jni_str!("getClassLoader"),
                    jni_sig!("()Ljava/lang/ClassLoader;"),
                    &[],
                )?
                .l()?;
            let java_name = env.new_string(DSD_CLASS_NAME.replace('/', "."))?;
            let class_obj = env
                .call_method(
                    &loader,
                    jni_str!("loadClass"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Class;"),
                    &[jni::objects::JValue::Object(&java_name)],
                )?
                .l()?;
            let class = unsafe { jni::objects::JClass::from_raw(env, class_obj.into_raw()) };
            cache_class(env, &class);
            Ok(class)
        }
    }
}

#[cfg(target_os = "android")]
fn cache_class(env: &mut jni::Env<'_>, class: &jni::objects::JClass<'_>) {
    if get_cached_class().is_some() {
        return;
    }
    match env.new_global_ref(class) {
        Ok(global_ref) => {
            let raw = global_ref.as_obj().as_raw() as *mut std::ffi::c_void;
            DSD_TRACK_CLASS.store(raw, Ordering::Release);
            std::mem::forget(global_ref);
            log::info!("[DSD-NATIVE] Cached DsdAudioTrackManager class reference");
        }
        Err(e) => {
            log::warn!("[DSD-NATIVE] Failed to cache class global ref: {}", e);
        }
    }
}

pub fn dsd_track_preload_class() {
    #[cfg(target_os = "android")]
    {
        if get_cached_class().is_some() {
            return;
        }
        let result = with_jni_env(|env| {
            let class = env.find_class(JNIString::new(DSD_CLASS_NAME))?;
            cache_class(env, &class);
            Ok(())
        });
        if let Err(e) = result {
            log::warn!(
                "[DSD-NATIVE] Preload class failed (will retry with classloader): {}",
                e
            );
        }
    }
}

pub fn dsd_track_class_available() -> bool {
    #[cfg(target_os = "android")]
    {
        let cached = DSD_ENCODING_AVAILABLE.load(Ordering::Acquire);
        if cached {
            return true;
        }
        let available = with_jni_env(|env| {
            let class = find_dsd_class(env)?;
            let result =
                env.call_static_method(&class, jni_str!("isEncodingDsdAvailable"), jni_sig!("()Z"), &[])?;
            Ok(result.z()?)
        })
        .unwrap_or_else(|e| {
            log::warn!("[DSD-NATIVE] ENCODING_DSD availability probe failed: {}", e);
            false
        });
        if available {
            DSD_ENCODING_AVAILABLE.store(true, Ordering::Release);
        }
        available
    }
    #[cfg(not(target_os = "android"))]
    {
        false
    }
}

pub fn set_dsd_encoding_available(available: bool) {
    DSD_ENCODING_AVAILABLE.store(available, Ordering::Release);
}

pub fn is_dsd_encoding_cached_available() -> bool {
    DSD_ENCODING_AVAILABLE.load(Ordering::Acquire)
}

#[cfg(target_os = "android")]
fn with_jni_env<F, R>(f: F) -> Result<R, String>
where
    F: FnOnce(&mut jni::Env<'_>) -> Result<R, jni::errors::Error>,
{
    let ctx = ndk_context::android_context();
    let vm = unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) };
    vm.attach_current_thread(|env| {
        let result = f(env);
        let _ = env.exception_clear();
        result
    })
    .map_err(|e| format!("JNI call failed: {}", e))
}
