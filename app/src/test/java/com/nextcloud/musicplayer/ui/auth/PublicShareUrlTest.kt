package com.nextcloud.musicplayer.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PublicShareUrlTest {

    @Test
    fun testParseStandardPublicShareUrl() {
        val url = "https://cloud.example.com/s/AbCdEf123456"
        val parsed = LoginViewModel.parsePublicShareUrl(url)
        assertNotNull(parsed)
        assertEquals("https://cloud.example.com", parsed?.baseUrl)
        assertEquals("AbCdEf123456", parsed?.shareToken)
    }

    @Test
    fun testParseTrailingSlashUrl() {
        val url = "https://cloud.example.com/s/AbCdEf123456/"
        val parsed = LoginViewModel.parsePublicShareUrl(url)
        assertNotNull(parsed)
        assertEquals("https://cloud.example.com", parsed?.baseUrl)
        assertEquals("AbCdEf123456", parsed?.shareToken)
    }

    @Test
    fun testParseIndexPhpUrl() {
        val url = "https://cloud.example.com/index.php/s/AbCdEf123456"
        val parsed = LoginViewModel.parsePublicShareUrl(url)
        assertNotNull(parsed)
        assertEquals("https://cloud.example.com", parsed?.baseUrl)
        assertEquals("AbCdEf123456", parsed?.shareToken)
    }

    @Test
    fun testParseSubpathWithPortUrl() {
        val url = "http://192.168.1.100:8080/nextcloud/s/token_123-abc"
        val parsed = LoginViewModel.parsePublicShareUrl(url)
        assertNotNull(parsed)
        assertEquals("http://192.168.1.100:8080/nextcloud", parsed?.baseUrl)
        assertEquals("token_123-abc", parsed?.shareToken)
    }

    @Test
    fun testInvalidUrls() {
        assertNull(LoginViewModel.parsePublicShareUrl("not_a_url"))
        assertNull(LoginViewModel.parsePublicShareUrl("https://cloud.example.com/other/path"))
        assertNull(LoginViewModel.parsePublicShareUrl("ftp://cloud.example.com/s/token"))
    }
}
