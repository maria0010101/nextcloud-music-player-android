package com.nextcloud.musicplayer.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(serverUrl: String, loginName: String, appPassword: String) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        sharedPreferences.edit()
            .putString(KEY_SERVER_URL, cleanUrl)
            .putString(KEY_LOGIN_NAME, loginName.trim())
            .putString(KEY_APP_PASSWORD, appPassword.trim())
            .apply()
    }

    fun getServerUrl(): String? = sharedPreferences.getString(KEY_SERVER_URL, null)

    fun getLoginName(): String? = sharedPreferences.getString(KEY_LOGIN_NAME, null)

    fun getAppPassword(): String? = sharedPreferences.getString(KEY_APP_PASSWORD, null)

    fun saveSelectedMusicFolder(folderPath: String) {
        sharedPreferences.edit()
            .putString(KEY_MUSIC_FOLDER, folderPath.trim())
            .apply()
    }

    fun getSelectedMusicFolder(): String {
        return sharedPreferences.getString(KEY_MUSIC_FOLDER, "") ?: ""
    }

    fun getBasicAuthHeader(): String? {
        val username = getLoginName() ?: return null
        val password = getAppPassword() ?: return null
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    fun getWebDavBaseUrl(): String? {
        val server = getServerUrl() ?: return null
        val username = getLoginName() ?: return null
        return "$server/remote.php/dav/files/$username"
    }

    fun hasCredentials(): Boolean {
        return !getServerUrl().isNullOrBlank() &&
                !getLoginName().isNullOrBlank() &&
                !getAppPassword().isNullOrBlank()
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "nextcloud_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LOGIN_NAME = "login_name"
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_MUSIC_FOLDER = "music_folder"
    }
}
