package com.nextcloud.musicplayer.audio.flick

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Android owns permission and the original FD; Rust owns its duplicate. */
class FlickUsbHost(context: Context, private val onDetached: () -> Unit) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private val permissionAction = "${this.context.packageName}.FLICK_USB_PERMISSION"
    private var pending: ((String?) -> Unit)? = null
    @Volatile private var selected: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    @Volatile private var connectionDevice: String? = null
    private var closed = false
    private val timeout = Runnable { finishPermission("USB 授權逾時，請重新播放") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDevice()
                    if (device?.deviceName != selected?.deviceName) return
                    finishPermission(if (manager.hasPermission(device)) null else "未取得 USB DAC 使用權限")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val name = intent.usbDevice()?.deviceName
                    if (name == selected?.deviceName || name == connectionDevice) {
                        finishPermission("USB DAC 已移除")
                        selected = null
                        onDetached()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") this.context.registerReceiver(receiver, filter)
    }

    /** Main thread; the Android permission dialog completes asynchronously. */
    fun requestPermission(callback: (String?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) { callback("USB 後端已關閉"); return }
        finishPermission("USB 授權要求已取代")
        val devices = manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
        val device = devices.firstOrNull { it.deviceName == selected?.deviceName } ?: devices.firstOrNull()
        if (device == null) { callback("bit-perfect 需要已連接且相容的 USB DAC"); return }
        selected = device
        if (manager.hasPermission(device)) { callback(null); return }
        pending = callback
        val intent = Intent(permissionAction).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        try {
            manager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, flags))
            handler.postDelayed(timeout, 60_000)
        } catch (_: Exception) {
            finishPermission("無法顯示 USB DAC 授權，請重新連接裝置後再試")
        }
    }

    /** Native worker only. Call after the previous Rust stream stopped. */
    fun activateOnWorker() {
        val device = selected ?: error("USB DAC 已移除")
        check(manager.hasPermission(device)) { "USB DAC 權限已失效" }
        if (connectionDevice == device.deviceName && connection != null) return
        closeConnectionOnWorker()
        val opened = manager.openDevice(device) ?: error("無法開啟 USB DAC")
        try {
            val result = JSONObject(NativeFlick.registerUsb(opened.fileDescriptor, device.vendorId,
                device.productId, device.productName.orEmpty(), device.manufacturerName.orEmpty(),
                runCatching { device.serialNumber.orEmpty() }.getOrDefault(""), device.deviceName))
            result.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
            connection = opened
            connectionDevice = device.deviceName
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    /** Stop/clear Rust before closing the FD from which it duplicated its handle. */
    fun closeConnectionOnWorker() {
        if (connection != null) NativeFlick.clearUsb()
        connection?.close()
        connection = null
        connectionDevice = null
    }

    fun cancelPermission() = finishPermission("USB 授權要求已取消")

    /** Main thread. Worker must subsequently call closeConnectionOnWorker. */
    fun unregister() {
        closed = true
        cancelPermission()
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun finishPermission(error: String?) {
        handler.removeCallbacks(timeout)
        val callback = pending
        pending = null
        callback?.invoke(error)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
