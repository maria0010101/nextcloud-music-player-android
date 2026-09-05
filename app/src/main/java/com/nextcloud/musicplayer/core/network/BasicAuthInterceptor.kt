package com.nextcloud.musicplayer.core.network

import com.nextcloud.musicplayer.core.security.SecurePreferencesManager
import okhttp3.Interceptor
import okhttp3.Response

class BasicAuthInterceptor(
    private val prefsManager: SecurePreferencesManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        prefsManager.getBasicAuthHeader()?.let { authHeader ->
            requestBuilder.header("Authorization", authHeader)
        }

        requestBuilder.header("User-Agent", "NextcloudMusicPlayer/1.0 (Android)")
        requestBuilder.header("OCS-APIREQUEST", "true")

        return chain.proceed(requestBuilder.build())
    }
}
