package com.nextcloud.musicplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object ArtworkHelper {

    private const val TAG = "ArtworkHelper"
    private const val MAX_ARTWORK_DIMENSION = 512
    private const val JPEG_QUALITY = 85

    // 記憶體快取：最多保留 20 張專輯封面的 JPEG Byte 陣列 (每張約 30~50KB)
    private val artworkCache = LruCache<String, ByteArray>(20)

    /**
     * 從快取中立即取得封面 Byte 陣列 (無任何 I/O)
     */
    fun getCachedArtworkBytes(coverUrl: String?): ByteArray? {
        if (coverUrl.isNullOrBlank()) return null
        return artworkCache.get(coverUrl)
    }

    /**
     * 同步快速檢查：若為本地檔案或已存在於 Coil 記憶體快取，立即讀取並回傳
     * 避免阻塞主執行緒
     */
    fun getOrLoadArtworkBytesSync(context: Context, coverUrl: String?): ByteArray? {
        if (coverUrl.isNullOrBlank()) return null

        // 1. 記憶體快取命中
        artworkCache.get(coverUrl)?.let { return it }

        // 2. 本機直接檔案 (以 / 開頭或 file:// 開頭)
        val localFilePath = when {
            coverUrl.startsWith("file://") -> coverUrl.removePrefix("file://")
            coverUrl.startsWith("/") -> coverUrl
            else -> null
        }

        if (localFilePath != null) {
            val file = File(localFilePath)
            if (file.exists() && file.isFile) {
                try {
                    val bytes = decodeFileToJpegBytes(file)
                    if (bytes != null) {
                        artworkCache.put(coverUrl, bytes)
                        return bytes
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync load local cover failed: $coverUrl", e)
                }
            }
        }

        // 3. 檢查 Coil 記憶體快取
        try {
            val memoryCache = context.imageLoader.memoryCache
            val cacheKey = coverUrl
            val cachedValue = memoryCache?.get(coil.memory.MemoryCache.Key(cacheKey))
            val bitmap = cachedValue?.bitmap
            if (bitmap != null) {
                val bytes = compressBitmapToJpegBytes(bitmap)
                artworkCache.put(coverUrl, bytes)
                return bytes
            }
        } catch (e: Exception) {
            // ignore memory cache lookup errors
        }

        return null
    }

    /**
     * 非同步載入封面並壓縮為 JPEG Byte 陣列：
     * 優先順序：
     * 1. 記憶體快取 (artworkCache)
     * 2. 本地儲存檔案 (File / content://)
     * 3. Coil 圖片載入引擎 (包含本機快取與帶有 Basic Auth 的 WebDAV 遠端請求)
     */
    suspend fun getOrLoadArtworkBytes(context: Context, coverUrl: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            if (coverUrl.isNullOrBlank()) return@withContext null

            // 1. 檢查快取
            artworkCache.get(coverUrl)?.let { return@withContext it }

            // 2. 本地 File 檢查
            val localPath = when {
                coverUrl.startsWith("file://") -> coverUrl.removePrefix("file://")
                coverUrl.startsWith("/") -> coverUrl
                else -> null
            }
            if (localPath != null) {
                val file = File(localPath)
                if (file.exists() && file.isFile) {
                    val bytes = decodeFileToJpegBytes(file)
                    if (bytes != null) {
                        artworkCache.put(coverUrl, bytes)
                        return@withContext bytes
                    }
                }
            }

            // 3. SAF content:// Uri
            if (coverUrl.startsWith("content://")) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(coverUrl))?.use { input ->
                        val originalBmp = BitmapFactory.decodeStream(input)
                        if (originalBmp != null) {
                            val scaled = scaleDownBitmap(originalBmp, MAX_ARTWORK_DIMENSION)
                            val bytes = compressBitmapToJpegBytes(scaled)
                            artworkCache.put(coverUrl, bytes)
                            return@withContext bytes
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading content uri artwork: $coverUrl", e)
                }
            }

            // 4. 透過 Coil ImageLoader 請求 (已整合 WebDAV 驗證與硬體加速關閉)
            try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)
                    .allowHardware(false) // 必須使用軟體 Bitmap 才能進行 ByteArray 壓縮
                    .build()

                val result = context.imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = when (val drawable = result.drawable) {
                        is BitmapDrawable -> drawable.bitmap
                        else -> {
                            val bmp = Bitmap.createBitmap(
                                drawable.intrinsicWidth.coerceAtLeast(1),
                                drawable.intrinsicHeight.coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = Canvas(bmp)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bmp
                        }
                    }

                    val scaled = scaleDownBitmap(bitmap, MAX_ARTWORK_DIMENSION)
                    val bytes = compressBitmapToJpegBytes(scaled)
                    artworkCache.put(coverUrl, bytes)
                    Log.d(TAG, "Successfully loaded and cached artwork for: $coverUrl (${bytes.size} bytes)")
                    return@withContext bytes
                } else {
                    Log.w(TAG, "Coil execute did not return SuccessResult for: $coverUrl")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading artwork via Coil for: $coverUrl", e)
            }

            return@withContext null
        }

    fun invalidate(coverUrl: String?) {
        if (!coverUrl.isNullOrBlank()) {
            artworkCache.remove(coverUrl)
        }
    }

    private fun decodeFileToJpegBytes(file: File): ByteArray? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val originalW = options.outWidth
        val originalH = options.outHeight
        if (originalW <= 0 || originalH <= 0) return null

        var inSampleSize = 1
        while ((originalW / inSampleSize) > MAX_ARTWORK_DIMENSION || (originalH / inSampleSize) > MAX_ARTWORK_DIMENSION) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val sampledBmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        val scaled = scaleDownBitmap(sampledBmp, MAX_ARTWORK_DIMENSION)
        return compressBitmapToJpegBytes(scaled)
    }

    private fun scaleDownBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun compressBitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }
}
