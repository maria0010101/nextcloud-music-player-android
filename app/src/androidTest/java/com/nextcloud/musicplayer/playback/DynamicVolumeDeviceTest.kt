package com.nextcloud.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.audio.PlaybackVolume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class DynamicVolumeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(100)
        }
        fail(label)
    }

    @Test fun bothEnginesKeepIdenticalGainAcrossForegroundBackgroundAndFollowSystemChanges() {
        val app = context.applicationContext as NextcloudMusicApp
        val settings = app.appSettingsDataStore
        val saved = runBlocking { settings.playbackSettings.first() }
        val prefs = context.getSharedPreferences(PlaybackVolume.PREFS_NAME, Context.MODE_PRIVATE)
        val oldVolumePrefs = prefs.all.toMap()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val original = audio.getStreamVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val fixture = java.io.File(context.cacheDir, "dynamic-silence.wav")
        val pcmBytes = 48000 * 2 * 2 * 120
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(pcmBytes)
        java.io.RandomAccessFile(fixture, "rw").use { it.write(header.array()); it.setLength(44L + pcmBytes) }
        var controller: MediaController? = null
        try {
            runBlocking { settings.saveVolumeSteps(0) }
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            controller = future!!.get(20, TimeUnit.SECONDS)
            val player = controller!!
            main { player.pause(); player.clearMediaItems() } // never emit audible media
            for (engine in listOf("system", "flick")) {
                runBlocking { settings.saveAudioEngine(engine); settings.saveVolumeSteps(100) }
                Thread.sleep(600)
                main {
                    player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(fixture)))
                    player.prepare()
                }
                waitFor("silent source ready") { player.playbackState == androidx.media3.common.Player.STATE_READY }
                main { player.play() }
                waitFor("silent source playing") { player.isPlaying }
                shell("am start -n com.nextcloud.musicplayer/.MainActivity")
                Thread.sleep(700)
                for (fraction in listOf(0.21f, 0.43f, 0.67f, 0f)) {
                    main { PlaybackVolume.saveFraction(context, fraction) }
                    val expected = if (fraction == 0f) 0 else ceil((fraction * max).toDouble()).toInt()
                    waitFor("fine request maps to system index: $engine/$fraction") {
                        audio.getStreamVolume(stream) == expected && (fraction != 0f || player.volume == 0f)
                    }
                    Thread.sleep(250)
                    var gain = 0f
                    main { gain = player.volume }
                    assertTrue("residual never amplifies", gain in 0f..1f)
                    shell("input keyevent 3") // HOME, real Activity lifecycle transition
                    Thread.sleep(500)
                    main { assertEquals("background gain $engine", gain, player.volume, 0.00001f); assertTrue("background remains playing", player.isPlaying) }
                    assertEquals(expected, audio.getStreamVolume(stream))
                    shell("am start -n com.nextcloud.musicplayer/.MainActivity")
                    Thread.sleep(500)
                    main { assertEquals("foreground gain $engine", gain, player.volume, 0.00001f) }
                    assertEquals(expected, audio.getStreamVolume(stream))
                }
                shell("input keyevent 3")
                audio.setStreamVolume(stream, max / 3, 0)
                waitFor("background system adjustment drops stale residual") {
                    kotlin.math.abs(PlaybackVolume.readFraction(context) - (max / 3).toFloat() / max) < 0.0001f &&
                        kotlin.math.abs(player.volume - 1f) < 0.0001f
                }
                val backgroundStep = audio.getStreamVolume(stream)
                shell("am start -n com.nextcloud.musicplayer/.MainActivity")
                Thread.sleep(500)
                main { assertEquals(1f, player.volume, 0.0001f) }
                assertEquals(backgroundStep, audio.getStreamVolume(stream))
                runBlocking { settings.saveVolumeSteps(0) }
                waitFor("off restores unity") { player.volume == 1f }
                assertEquals(backgroundStep, audio.getStreamVolume(stream))
            }
            runBlocking { settings.saveAudioEngine("flick"); settings.saveVolumeSteps(-1) }
            Thread.sleep(500)
            val strictSystem = audio.getStreamVolume(stream)
            main { PlaybackVolume.saveFraction(context, 0.93f) }
            Thread.sleep(400)
            assertEquals("strict does not map system level", strictSystem, audio.getStreamVolume(stream))
            main { assertEquals(1f, player.volume, 0f) }
        } finally {
            runBlocking { settings.saveVolumeSteps(0) }
            Thread.sleep(250)
            audio.setStreamVolume(stream, original, 0)
            prefs.edit().clear().apply {
                for ((key, value) in oldVolumePrefs) when (value) {
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }.commit()
            runBlocking { settings.saveAudioEngine(saved.audioEngine); settings.saveVolumeSteps(saved.volumeSteps) }
            Thread.sleep(300)
            main { controller?.pause(); controller?.stop(); controller?.clearMediaItems(); controller?.release() }
            fixture.delete()
        }
    }
}
