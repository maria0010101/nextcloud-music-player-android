package com.nextcloud.musicplayer.audio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class HeadphoneProfile(
    val name: String,
    val category: String,
    val preamp: Float,
    val bands: List<Pair<Int, Float>>
)

class SoundProfileManager(private val context: Context) {

    private val TAG = "SoundProfileManager"
    private val PREFS_CUSTOM = "custom_sound_profiles"

    @Volatile
    private var cachedProfiles: List<HeadphoneProfile>? = null

    /**
     * Load headphone sound profiles from gzipped asset file headphones.dat
     */
    fun loadProfiles(): List<HeadphoneProfile> {
        cachedProfiles?.let { return it }

        synchronized(this) {
            cachedProfiles?.let { return it }
            return try {
                val stream = context.assets.open("headphones.dat")
                val gzip = GZIPInputStream(stream)
                val json = InputStreamReader(gzip, Charsets.UTF_8).readText()
                gzip.close()

                val array = JSONArray(json)
                val list = ArrayList<HeadphoneProfile>(array.length())

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val bandsArray = obj.getJSONArray("b")
                    val bands = ArrayList<Pair<Int, Float>>(bandsArray.length())
                    for (j in 0 until bandsArray.length()) {
                        val band = bandsArray.getJSONArray(j)
                        bands.add(band.getInt(0) to band.getDouble(1).toFloat())
                    }
                    list.add(
                        HeadphoneProfile(
                            name = obj.getString("n"),
                            category = obj.optString("c", "headphone"),
                            preamp = obj.optDouble("p", 0.0).toFloat(),
                            bands = bands
                        )
                    )
                }

                cachedProfiles = list
                Log.d(TAG, "已成功載入 ${list.size} 組 AutoEq 耳機音質校準設定檔")
                list
            } catch (e: Exception) {
                Log.e(TAG, "載入 headphones.dat 失敗", e)
                emptyList()
            }
        }
    }

    /**
     * Search profiles with query matching model names
     */
    fun searchProfiles(query: String): List<HeadphoneProfile> {
        val all = loadProfiles()
        if (query.isBlank()) return all.take(50)
        val q = query.trim().lowercase()
        return all.filter { it.name.lowercase().contains(q) }.take(50)
    }

    fun findProfile(name: String): HeadphoneProfile? {
        val custom = loadCustomProfile(name)
        if (custom != null) return custom
        return loadProfiles().find { it.name.equals(name, ignoreCase = true) }
    }

    fun isCustomProfile(name: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE)
        return prefs.contains(name)
    }

    fun saveCustomProfile(name: String, bands: List<Pair<Int, Float>>) {
        val prefs = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE)
        val json = JSONObject()
        val bandsArray = JSONArray()
        for ((freq, gain) in bands) {
            val b = JSONArray()
            b.put(freq)
            b.put(gain.toDouble())
            bandsArray.put(b)
        }
        json.put("bands", bandsArray)
        prefs.edit().putString(name, json.toString()).apply()
    }

    fun deleteCustomProfile(name: String) {
        val prefs = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE)
        prefs.edit().remove(name).apply()
    }

    private fun loadCustomProfile(name: String): HeadphoneProfile? {
        val prefs = context.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE)
        val json = prefs.getString(name, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val bandsArray = obj.getJSONArray("bands")
            val bands = ArrayList<Pair<Int, Float>>(bandsArray.length())
            for (i in 0 until bandsArray.length()) {
                val b = bandsArray.getJSONArray(i)
                bands.add(b.getInt(0) to b.getDouble(1).toFloat())
            }
            HeadphoneProfile(name, "custom", 0f, bands)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: SoundProfileManager? = null

        fun getInstance(context: Context): SoundProfileManager {
            return instance ?: synchronized(this) {
                instance ?: SoundProfileManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
