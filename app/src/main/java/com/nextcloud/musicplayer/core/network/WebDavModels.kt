package com.nextcloud.musicplayer.core.network

data class WebDavItem(
    val href: String,
    val displayName: String,
    val isCollection: Boolean,
    val contentLength: Long = 0L,
    val contentType: String? = null,
    val lastModified: String? = null,
    val etag: String? = null
) {
    val fileExtension: String
        get() {
            val cleanName = displayName.ifBlank { href.trimEnd('/').substringAfterLast('/') }
            return cleanName.substringAfterLast('.', "").lowercase()
        }

    val isAudioFile: Boolean
        get() {
            if (isCollection) return false
            val ext = fileExtension
            if (ext in listOf("mp3", "flac", "aac", "wav", "ogg", "m4a", "opus", "wma")) {
                return true
            }
            return contentType?.startsWith("audio/") == true
        }

    val isImageFile: Boolean
        get() {
            if (isCollection) return false
            val ext = fileExtension
            if (ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")) {
                return true
            }
            return contentType?.startsWith("image/") == true
        }

    val audioFormat: String
        get() {
            return when (fileExtension) {
                "mp3" -> "MP3"
                "flac" -> "FLAC"
                "aac" -> "AAC"
                "wav" -> "WAV"
                "ogg" -> "OGG"
                "m4a" -> "M4A"
                "opus" -> "OPUS"
                else -> fileExtension.uppercase().ifBlank { "AUDIO" }
            }
        }
}
