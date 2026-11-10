package com.nextcloud.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.musicplayer.NextcloudMusicApp
import com.nextcloud.musicplayer.audio.flick.NativeFlick
import com.nextcloud.musicplayer.core.settings.AppSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Silent fixture only: real device decoder/clock checks without changing the user's system volume. */
@RunWith(AndroidJUnit4::class)
class FlickPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(description: String, timeoutMs: Long = 20000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            main { ok = predicate() }
            if (ok) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }
    private fun silentWav(): File {
        val samples = 48000 * 2 * 8
        val pcmBytes = samples * 2
        val b = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(pcmBytes)
        return File(context.cacheDir, "flick-silent-test.wav").apply { writeBytes(b.array()) }
    }

    @Test fun sessionSwitchRustDecodePauseSeekAndSystemVolumePolicy() {
        val app = context.applicationContext as NextcloudMusicApp
        val settings = app.appSettingsDataStore
        val saved = runBlocking { settings.playbackSettings.first() }
        val fixture = silentWav()
        var controller: MediaController? = null
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        try {
            runBlocking { settings.saveAudioEngine(AppSettingsDataStore.AUDIO_ENGINE_SYSTEM); settings.saveVolumeSteps(0) }
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            controller = future!!.get(20, TimeUnit.SECONDS)
            val player = controller!!
            await("session system mode") { player.isCommandAvailable(Player.COMMAND_SET_VOLUME) }
            main {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                player.prepare()
            }
            await("system ready") { player.playbackState == Player.STATE_READY }
            main { assertEquals(1f, player.volume, 0.0001f) }
            runBlocking { settings.saveAudioEngine(AppSettingsDataStore.AUDIO_ENGINE_FLICK) }
            await("Rust engine ready") {
                player.playbackState == Player.STATE_READY && player.currentTracks.groups.any { group ->
                    (0 until group.length).any { group.getTrackFormat(it).id == "flick" && group.getTrackFormat(it).sampleRate == 48000 }
                }
            }
            assertTrue("native library packaged", NativeFlick.isAvailable)
            // Prepare must not emit/play; the native clock stays paused until Play.
            Thread.sleep(350)
            val paused = JSONObject(NativeFlick.snapshot())
            assertFalse(paused.toString(), paused.has("error"))
            assertEquals("paused", paused.optString("state"))
            main { assertFalse(player.playWhenReady); player.play() }
            await("Rust clock advances") { player.isPlaying && player.currentPosition > 250 }
            main { player.pause() }
            await("Rust pause") { !player.isPlaying }
            main { player.seekTo(3000) }
            await("seek updates") { player.currentPosition >= 2900 }
            runBlocking { settings.saveVolumeSteps(100) }
            await("Rust fine gain") {
                player.volume = 0.125f
                kotlin.math.abs(player.volume - 0.125f) < 0.001f
            }
            runBlocking { settings.saveVolumeSteps(0) }
            await("off restores unity") { kotlin.math.abs(player.volume - 1f) < 0.001f }
            // No DAC present: strict should fail visibly and never fall back to speaker.
            val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (manager.deviceList.isEmpty()) {
                runBlocking { settings.saveVolumeSteps(-1) }
                await("strict no DAC error") { player.playerError != null }
                main { assertFalse(player.isPlaying); assertFalse(player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) }
            }
            runBlocking { settings.saveAudioEngine(AppSettingsDataStore.AUDIO_ENGINE_SYSTEM) }
            await("switch back system") { player.playerError == null && player.playbackState == Player.STATE_READY }
            main { assertEquals(1f, player.volume, 0.0001f); player.stop(); player.clearMediaItems() }
        } finally {
            main { controller?.pause(); controller?.release() }
            runBlocking { settings.saveAudioEngine(saved.audioEngine); settings.saveVolumeSteps(saved.volumeSteps) }
            fixture.delete()
        }
    }
    @Test fun authenticatedNextcloudStreamAndSeekUseExistingCache() {
        val app = context.applicationContext as NextcloudMusicApp
        val settings = app.appSettingsDataStore
        val saved = runBlocking { settings.playbackSettings.first() }
        // Use an existing indexed remote source without printing its private URL/credentials.
        val cursor = app.database.openHelper.readableDatabase.query(
            "SELECT streamUrl FROM tracks WHERE streamUrl LIKE 'http%' ORDER BY CASE WHEN format='FLAC' THEN 0 ELSE 1 END LIMIT 1"
        )
        val source = cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        org.junit.Assume.assumeTrue("Needs existing signed-in Nextcloud library", source != null)
        var player: MediaController? = null
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        try {
            runBlocking { settings.saveAudioEngine(AppSettingsDataStore.AUDIO_ENGINE_FLICK); settings.saveVolumeSteps(100) }
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            player = future!!.get(20, TimeUnit.SECONDS)
            val controller = player!!
            main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("flick-network-test").setUri(source!!).build())
                controller.prepare()
            }
            await("authenticated Rust stream ready", 60000) {
                assertNull("Network decoder error", controller.playerError)
                controller.playbackState == Player.STATE_READY && controller.currentTracks.groups.any { group ->
                    (0 until group.length).any { group.getTrackFormat(it).id == "flick" }
                }
            }
            await("mute native gain before network playback") { controller.volume = 0f; controller.volume == 0f }
            main { controller.play() }
            await("network playback clock", 15000) { controller.isPlaying && controller.currentPosition > 250 }
            main { controller.pause(); controller.seekTo(30000) }
            await("network Range seek", 30000) { assertNull(controller.playerError); controller.currentPosition >= 29900 }
            main { controller.play() }
            await("seeked network playback", 30000) { assertNull(controller.playerError); controller.isPlaying && controller.currentPosition > 30250 }
            main { controller.pause(); controller.stop(); controller.clearMediaItems() }
            assertTrue("Media3 shared cache used", app.playbackCacheManager.getCacheSizeBytes() > 0)
        } finally {
            main { player?.pause(); player?.release() }
            runBlocking { settings.saveAudioEngine(saved.audioEngine); settings.saveVolumeSteps(saved.volumeSteps) }
        }
    }

    @Test fun physicalKeysFollowSystemFineAndStrictPolicies() {
        val app = context.applicationContext as NextcloudMusicApp
        val settings = app.appSettingsDataStore
        val saved = runBlocking { settings.playbackSettings.first() }
        val fraction = com.nextcloud.musicplayer.audio.PlaybackVolume.readFraction(context)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_MUSIC
        val originalSystem = audio.getStreamVolume(stream)
        // MIUI may restrict background Activity launches by the instrumentation app.
        // Use the authorized shell launch, and observe lifecycle rather than requiring
        // Compose artwork/marquee animations to become completely idle.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.nextcloud.musicplayer/.MainActivity"
        )).use { it.readBytes() }
        var activity: com.nextcloud.musicplayer.MainActivity? = null
        await("foreground Activity resumed") {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<com.nextcloud.musicplayer.MainActivity>().firstOrNull()
            activity != null
        }
        var vm: com.nextcloud.musicplayer.ui.player.PlayerViewModel? = null
        main { vm = androidx.lifecycle.ViewModelProvider(activity!!)[com.nextcloud.musicplayer.ui.player.PlayerViewModel::class.java] }
        fun choose(engine: String, steps: Int) {
            runBlocking { settings.saveAudioEngine(engine); settings.saveVolumeSteps(steps) }
            await("foreground key policy $steps") {
                vm!!.volumeControlsReady && vm!!.playbackSettings.value.audioEngine == engine && vm!!.playbackSettings.value.volumeSteps == steps
            }
        }
        try {
            choose(AppSettingsDataStore.AUDIO_ENGINE_SYSTEM, 0)
            // MIUI's first press may only show the panel; allow subsequent real adjustment.
            val key = if (originalSystem == audio.getStreamMaxVolume(stream)) android.view.KeyEvent.KEYCODE_VOLUME_DOWN else android.view.KeyEvent.KEYCODE_VOLUME_UP
            repeat(3) { instrumentation.sendKeyDownUpSync(key); Thread.sleep(200) }
            assertNotEquals("off delegates real keys to Android", originalSystem, audio.getStreamVolume(stream))
            audio.setStreamVolume(stream, originalSystem, 0)
            choose(AppSettingsDataStore.AUDIO_ENGINE_FLICK, 25)
            val fineBefore = com.nextcloud.musicplayer.audio.PlaybackVolume.readFraction(context)
            val fineKey = if (fineBefore >= 1f) android.view.KeyEvent.KEYCODE_VOLUME_DOWN else android.view.KeyEvent.KEYCODE_VOLUME_UP
            instrumentation.sendKeyDownUpSync(fineKey)
            assertEquals("fine must not change Android volume", originalSystem, audio.getStreamVolume(stream))
            assertNotEquals("fine key changes app fraction", fineBefore, com.nextcloud.musicplayer.audio.PlaybackVolume.readFraction(context))
            choose(AppSettingsDataStore.AUDIO_ENGINE_FLICK, -1)
            val strictBefore = com.nextcloud.musicplayer.audio.PlaybackVolume.readFraction(context)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_UP)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            assertEquals("strict ignores system volume keys", originalSystem, audio.getStreamVolume(stream))
            assertEquals("strict ignores app volume keys", strictBefore, com.nextcloud.musicplayer.audio.PlaybackVolume.readFraction(context), 0f)
        } finally {
            com.nextcloud.musicplayer.audio.PlaybackVolume.saveFraction(context, fraction)
            audio.setStreamVolume(stream, originalSystem, 0)
            runBlocking { settings.saveAudioEngine(saved.audioEngine); settings.saveVolumeSteps(saved.volumeSteps) }
            main { activity?.finish() }
        }
    }

}
