package com.nextcloud.musicplayer.data.repository

import com.nextcloud.musicplayer.data.local.entity.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSyncDiffTest {

    @Test
    fun testDiffDeletedAndNewPaths() {
        val localAlbumPaths = setOf(
            "/remote.php/dav/files/user/Music/Album1",
            "/remote.php/dav/files/user/Music/Album2",
            "/remote.php/dav/files/user/Music/Album3"
        )

        val remoteAlbumPaths = setOf(
            "/remote.php/dav/files/user/Music/Album2",
            "/remote.php/dav/files/user/Music/Album3",
            "/remote.php/dav/files/user/Music/Album4"
        )

        val deletedPaths = localAlbumPaths - remoteAlbumPaths
        val newPaths = remoteAlbumPaths - localAlbumPaths

        assertEquals(setOf("/remote.php/dav/files/user/Music/Album1"), deletedPaths)
        assertEquals(setOf("/remote.php/dav/files/user/Music/Album4"), newPaths)
    }

    @Test
    fun testCustomCoverPreservationDuringSync() {
        val existingCustom = AlbumEntity(
            id = "album_1",
            name = "Test Album",
            remotePath = "/remote.php/dav/files/user/Music/Album1",
            coverUrl = "/data/user/0/com.nextcloud.musicplayer/files/custom_covers/custom.jpg",
            isCustomLocalCover = true,
            trackCount = 10,
            etag = "etag_old"
        )

        val remoteCoverUrl = "https://example.com/Music/Album1/cover.jpg"

        // 自訂封面保護邏輯
        val finalCover = if (existingCustom.isCustomLocalCover && !existingCustom.coverUrl.isNullOrBlank()) {
            existingCustom.coverUrl
        } else {
            remoteCoverUrl
        }

        assertEquals(existingCustom.coverUrl, finalCover)
    }

    @Test
    fun testEtagMatching() {
        val existing = AlbumEntity(
            id = "album_1",
            name = "Test Album",
            remotePath = "/remote.php/dav/files/user/Music/Album1",
            coverUrl = "https://example.com/cover.jpg",
            etag = "etag_12345",
            lastModified = "Mon, 08 Sep 2026 00:00:00 GMT"
        )

        val sameEtag = "etag_12345"
        val diffEtag = "etag_99999"

        assertTrue(existing.etag == sameEtag)
        assertFalse(existing.etag == diffEtag)
    }

    @Test
    fun testNormalizePath() {
        val fullUrl = "https://wangshomenas002.ddns.net/remote.php/dav/files/maria/Music/Album%201/"
        val relativePath = "/remote.php/dav/files/maria/Music/Album 1"
        val bareRelative = "remote.php/dav/files/maria/Music/Album 1/"

        val normFull = WebDavSyncRepository.normalizePath(fullUrl)
        val normRel = WebDavSyncRepository.normalizePath(relativePath)
        val normBare = WebDavSyncRepository.normalizePath(bareRelative)

        assertEquals("/remote.php/dav/files/maria/Music/Album 1", normFull)
        assertEquals("/remote.php/dav/files/maria/Music/Album 1", normRel)
        assertEquals("/remote.php/dav/files/maria/Music/Album 1", normBare)
        assertEquals(normFull, normRel)
    }
}
