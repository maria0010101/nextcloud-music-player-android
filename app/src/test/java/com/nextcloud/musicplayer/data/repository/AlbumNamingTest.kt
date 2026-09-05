package com.nextcloud.musicplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNamingTest {

    @Test
    fun testFormatAlbumName() {
        // 1. Root / 01.mp3 -> "未分類單曲"
        assertEquals("未分類單曲", MusicRepository.formatAlbumName(emptyList()))

        // 2. Root / Album -> "[該資料夾名稱]" (例如 "Greatest Hits")
        assertEquals("Greatest Hits", MusicRepository.formatAlbumName(listOf("Greatest Hits")))

        // 3. Root / Parent / Sub -> "[母資料夾名稱] - [子資料夾名稱]" (例如 "Jay Chou - Fantasy")
        assertEquals("Jay Chou - Fantasy", MusicRepository.formatAlbumName(listOf("Jay Chou", "Fantasy")))

        // 4. Deeper than 2 levels -> "[母資料夾名稱] - [子路徑名稱]"
        assertEquals("Jay Chou - Fantasy - CD1", MusicRepository.formatAlbumName(listOf("Jay Chou", "Fantasy", "CD1")))
    }

    @Test
    fun testExtractRelativeSegments() {
        val rootUrl = "https://example.com/remote.php/dav/files/user/Music"

        // Root itself
        val segsRoot = MusicRepository.extractRelativeSegments(
            "https://example.com/remote.php/dav/files/user/Music",
            rootUrl
        )
        assertEquals(emptyList<String>(), segsRoot)

        // 1-level subfolder
        val segs1 = MusicRepository.extractRelativeSegments(
            "https://example.com/remote.php/dav/files/user/Music/Greatest Hits",
            rootUrl
        )
        assertEquals(listOf("Greatest Hits"), segs1)

        // 2-level subfolder with URL encoding
        val segs2 = MusicRepository.extractRelativeSegments(
            "/remote.php/dav/files/user/Music/Jay%20Chou/Fantasy/",
            rootUrl
        )
        assertEquals(listOf("Jay Chou", "Fantasy"), segs2)

        // 3-level subfolder
        val segs3 = MusicRepository.extractRelativeSegments(
            "https://example.com/remote.php/dav/files/user/Music/Jay Chou/Fantasy/CD1",
            rootUrl
        )
        assertEquals(listOf("Jay Chou", "Fantasy", "CD1"), segs3)
    }

    @Test
    fun testNaturalOrderComparator() {
        val list = listOf("10.mp3", "2.mp3", "1.mp3", "20.mp3", "track_2.flac", "track_10.flac", "track_1.flac")
        val sorted = list.sortedWith(NaturalOrderComparator)
        val expected = listOf("1.mp3", "2.mp3", "10.mp3", "20.mp3", "track_1.flac", "track_2.flac", "track_10.flac")
        assertEquals(expected, sorted)
    }
}
