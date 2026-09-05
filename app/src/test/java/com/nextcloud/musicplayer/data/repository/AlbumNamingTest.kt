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
    fun testCustomLevelsFormatAlbumNameByLevels() {
        val fullPath = "/Music/Rock/Classic/Pink Floyd/The Wall/01.mp3"
        val scanRootDir = "/Music"

        // 案例 A（勾選階層 2, 3, 4）：輸出 Rock - Classic - Pink Floyd
        val resultA = MusicRepository.formatAlbumNameByLevels(
            fullPath = fullPath,
            scanRootDir = scanRootDir,
            selectedLevels = setOf(2, 3, 4)
        )
        assertEquals("Rock - Classic - Pink Floyd", resultA)

        // 案例 B（勾選階層 1, 2, 5）：輸出 Music - Rock - The Wall
        val resultB = MusicRepository.formatAlbumNameByLevels(
            fullPath = fullPath,
            scanRootDir = scanRootDir,
            selectedLevels = setOf(1, 2, 5)
        )
        assertEquals("Music - Rock - The Wall", resultB)

        // 案例 C（勾選階層 3, 4，但檔案位於 /Music/Jazz/01.mp3 只有到階層 2）：篩選為空，回退顯示目前目錄 Jazz
        val resultC = MusicRepository.formatAlbumNameByLevels(
            fullPath = "/Music/Jazz/01.mp3",
            scanRootDir = scanRootDir,
            selectedLevels = setOf(3, 4)
        )
        assertEquals("Jazz", resultC)

        // 案例 D：全部取消勾選 (emptySet)，回退顯示當前目錄
        val resultD = MusicRepository.formatAlbumNameByLevels(
            fullPath = fullPath,
            scanRootDir = scanRootDir,
            selectedLevels = emptySet()
        )
        assertEquals("The Wall", resultD)

        // 案例 E：預設勾選 2, 3
        val resultE1 = MusicRepository.formatAlbumNameByLevels(
            fullPath = "/Music/Greatest Hits/01.mp3",
            scanRootDir = scanRootDir,
            selectedLevels = setOf(2, 3)
        )
        assertEquals("Greatest Hits", resultE1)

        val resultE2 = MusicRepository.formatAlbumNameByLevels(
            fullPath = "/Music/Jay Chou/Fantasy/01.mp3",
            scanRootDir = scanRootDir,
            selectedLevels = setOf(2, 3)
        )
        assertEquals("Jay Chou - Fantasy", resultE2)
    }
}
