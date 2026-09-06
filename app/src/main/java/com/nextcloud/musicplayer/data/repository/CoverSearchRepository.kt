package com.nextcloud.musicplayer.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ItunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int = 0,
    @SerializedName("results") val results: List<ItunesAlbumItem> = emptyList()
)

data class ItunesAlbumItem(
    @SerializedName("collectionId") val collectionId: Long? = null,
    @SerializedName("collectionName") val collectionName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    @SerializedName("artworkUrl100") val artworkUrl100: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("trackCount") val trackCount: Int? = null,
    @SerializedName("primaryGenreName") val primaryGenreName: String? = null
) {
    /**
     * 將 artworkUrl100 的 100x100bb.jpg 替換為 600x600bb.jpg 以取得高解析度封面
     */
    val highResArtworkUrl: String?
        get() {
            if (artworkUrl100.isNullOrBlank()) return null
            return artworkUrl100.replace(Regex("""\d+x\d+bb\.jpg"""), "600x600bb.jpg")
                .replace("100x100bb.jpg", "600x600bb.jpg")
        }
}

class CoverSearchRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    private val TAG = "CoverSearchRepository"

    /**
     * 透過 iTunes Search API 搜尋候選專輯封面
     *
     * @param query 專輯名稱或關鍵字
     * @param limit 限制回傳筆數 (預設 10)
     */
    suspend fun searchAlbums(
        query: String,
        limit: Int = 10
    ): Result<List<ItunesAlbumItem>> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=album&limit=$limit"
            Log.d(TAG, "Searching iTunes covers: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "NextcloudMusicPlayer/1.0 (Android)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("iTunes API returned HTTP ${response.code}: ${response.message}")
                )
            }

            val bodyString = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response from iTunes API"))

            val searchResponse = gson.fromJson(bodyString, ItunesSearchResponse::class.java)
            val filteredList = (searchResponse?.results ?: emptyList()).filter {
                !it.artworkUrl100.isNullOrBlank() || !it.collectionName.isNullOrBlank()
            }

            Log.d(TAG, "Found ${filteredList.size} candidate covers for '$trimmedQuery'")
            Result.success(filteredList)
        } catch (e: Exception) {
            Log.e(TAG, "Search albums failed for '$trimmedQuery'", e)
            Result.failure(e)
        }
    }

    /**
     * 下載目標圖片的位元組陣列 (ByteArray)
     */
    suspend fun downloadImageBytes(imageUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .get()
                .header("User-Agent", "NextcloudMusicPlayer/1.0 (Android)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Download image failed HTTP ${response.code}: ${response.message}")
                )
            }

            val bytes = response.body?.bytes()
                ?: return@withContext Result.failure(IOException("Image body is empty"))

            if (bytes.isEmpty()) {
                return@withContext Result.failure(IOException("Image bytes are 0 bytes"))
            }

            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Download image bytes failed: $imageUrl", e)
            Result.failure(e)
        }
    }
}
