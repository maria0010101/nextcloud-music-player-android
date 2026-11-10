package com.nextcloud.musicplayer.audio.flick

import android.content.Context

/** JNI boundary to the pinned Flick Rust decoder/output engine. Calls may block; use a worker. */
object NativeFlick {
    val isAvailable: Boolean = runCatching { System.loadLibrary("nextcloud_flick"); true }.getOrDefault(false)
    @JvmStatic external fun initEngine(context: Context): String
    @JvmStatic external fun open(uri: String, headersJson: String, strict: Boolean, gain: Float): String
    @JvmStatic external fun pause(): String
    @JvmStatic external fun resume(): String
    @JvmStatic external fun seek(seconds: Double): String
    @JvmStatic external fun setGain(gain: Float): String
    @JvmStatic external fun stop(): String
    @JvmStatic external fun release(): String
    @JvmStatic external fun snapshot(): String
    @JvmStatic external fun registerUsb(fd: Int, vendorId: Int, productId: Int, product: String, manufacturer: String, serial: String, deviceName: String): String
    @JvmStatic external fun clearUsb(): String
}
