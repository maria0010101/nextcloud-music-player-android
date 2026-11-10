package com.nextcloud.musicplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.nextcloud.musicplayer.audio.flick.FlickUsbHost
import com.nextcloud.musicplayer.audio.flick.NativeFlick
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** MediaSession adapter for the real Flick decoder/output engine, not a second ExoPlayer. */
@OptIn(UnstableApi::class)
class FlickPlayer(
    context: Context,
    strict: Boolean,
    initialGain: Float = 1f,
    private val headers: () -> Map<String, String> = { emptyMap() },
    private val resolveUri: (Uri) -> Uri = { it },
    private val onNativeReleased: () -> Unit = {}
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // Flick is process-global. Share ordering across service recreation as well as within
    // one player, so an old asynchronous release cannot tear down a new engine.
    private val worker = nativeExecutor
    private val generation = AtomicInteger()
    private val entries = mutableListOf<Entry>()
    private var nextUid = 0L
    private var index = 0
    private var position = 0L
    private var playback = Player.STATE_IDLE
    private var playerError: PlaybackException? = null
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var playlistMeta = MediaMetadata.EMPTY
    @Volatile private var desiredPlay = false
    @Volatile private var strictMode = strict
    @Volatile private var gain = if (strict) 1f else initialGain.coerceIn(0f, 1f)
    private var prepared = false
    private var nativeLoaded = false // worker thread only
    private var released = false
    private var pollPending = false
    private var lastSpec = Tracks.EMPTY
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ focus ->
            if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, main).build()
    private var focusHeld = false
    private var wakeRenewedAt = 0L
    private val wakeLock = (this.context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextcloudMusic:FlickPlayback")
        .apply { setReferenceCounted(false) }
    private val usb = FlickUsbHost(this.context, ::onUsbDetached)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (!released) pause() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            if (prepared && playback == Player.STATE_READY && !pollPending) {
                pollPending = true
                val token = generation.get()
                worker.execute {
                    val result = runCatching { checkResult(NativeFlick.snapshot()) }
                    main.post {
                        pollPending = false
                        if (!released && token == generation.get()) {
                            result.onSuccess { applySnapshot(it) }.onFailure { setFailure(it.message ?: "Rust 播放狀態讀取失敗") }
                        }
                    }
                }
            }
            main.postDelayed(this, 250)
        }
    }

    init {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            this.context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") this.context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        main.post(poll)
    }

    companion object {
        private val nativeExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlickNativePlayer").apply { isDaemon = true }
        }
    }

    private data class Entry(val uid: Long, var item: MediaItem, var durationMs: Long = C.TIME_UNSET)
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_PLAYLIST_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_VOLUME, Player.COMMAND_GET_AUDIO_ATTRIBUTES
        ).apply { if (!strictMode) add(Player.COMMAND_SET_VOLUME) }.build()
        val playlist = entries.mapIndexed { i, entry ->
            MediaItemData.Builder(entry.uid).setMediaItem(entry.item)
                .setIsSeekable(true).setDurationUs(if (entry.durationMs == C.TIME_UNSET) C.TIME_UNSET else entry.durationMs * 1000)
                .setTracks(if (i == index) lastSpec else Tracks.EMPTY).build()
        }
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(playlist).setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index.coerceIn(entries.indices))
            .setContentPositionMs(position).setContentBufferedPositionMs(PositionSupplier.getConstant(position))
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .setPlayWhenReady(desiredPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playback).setPlayerError(playerError)
            .setRepeatMode(repeat).setShuffleModeEnabled(shuffle)
            .setPlaylistMetadata(playlistMeta).setVolume(if (strictMode) 1f else gain).build()
    }

    /** Mode transitions stop first and require an explicit Play before unity-gain strict output. */
    fun updateVolumeMode(strict: Boolean, gain: Float) {
        verifyApplicationThread()
        val changed = strictMode != strict
        strictMode = strict
        this.gain = if (strict) 1f else gain.coerceIn(0f, 1f)
        if (changed) {
            desiredPlay = false
            abandonFocus()
            updateWakeLock()
            if (prepared) requestLoad(position)
        } else if (prepared) queueNative { checkResult(NativeFlick.setGain(this.gain)) }
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !requestFocus()) { setFailure("無法取得音訊焦點，播放已暫停"); return done() }
        desiredPlay = playWhenReady
        if (!playWhenReady) abandonFocus()
        if (prepared && playback == Player.STATE_READY) {
            val token = generation.get()
            queueNative(token) { checkResult(if (desiredPlay) NativeFlick.resume() else NativeFlick.pause()) }
        } else if (playWhenReady && (playback == Player.STATE_ENDED || playerError != null)) requestLoad(if (playback == Player.STATE_ENDED) 0 else position)
        updateWakeLock()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (entries.isEmpty()) playback = Player.STATE_ENDED else requestLoad(position)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        generation.incrementAndGet()
        usb.cancelPermission()
        desiredPlay = false
        prepared = false
        playback = Player.STATE_IDLE
        playerError = null
        abandonFocus()
        updateWakeLock()
        queueNative { if (nativeLoaded) { checkResult(NativeFlick.stop()); usb.closeConnectionOnWorker() } }
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        generation.incrementAndGet()
        released = true
        desiredPlay = false
        main.removeCallbacks(poll)
        usb.unregister()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        abandonFocus()
        updateWakeLock()
        val future = SettableFuture.create<Void>()
        worker.execute {
            try {
                if (nativeLoaded) { NativeFlick.stop(); NativeFlick.release(); usb.closeConnectionOnWorker() }
            } finally {
                runCatching { onNativeReleased() }
                main.post { future.set(null) }
            }
        }
        return future
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        if (!strictMode) {
            gain = volume.coerceIn(0f, 1f)
            if (prepared) queueNative { checkResult(NativeFlick.setGain(gain)) }
        }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> { shuffle = shuffleModeEnabled; return done() }
    override fun handleSetPlaylistMetadata(playlistMetadata: MediaMetadata): ListenableFuture<*> { playlistMeta = playlistMetadata; return done() }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val wasPrepared = prepared
        generation.incrementAndGet()
        usb.cancelPermission()
        entries.clear()
        entries.addAll(mediaItems.map { Entry(nextUid++, it) })
        index = if (entries.isEmpty()) 0 else (if (startIndex == C.INDEX_UNSET) 0 else startIndex).coerceIn(entries.indices)
        position = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0)
        lastSpec = Tracks.EMPTY
        playerError = null
        if (wasPrepared && entries.isNotEmpty()) requestLoad(position)
        else { prepared = false; playback = Player.STATE_IDLE; queueNative { if (nativeLoaded) NativeFlick.stop() } }
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val old = entries.getOrNull(this.index)?.uid
        entries.addAll(index.coerceIn(0, entries.size), mediaItems.map { Entry(nextUid++, it) })
        this.index = old?.let { uid -> entries.indexOfFirst { it.uid == uid }.takeIf { it >= 0 } } ?: 0
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val old = entries.getOrNull(index)?.uid
        val moved = entries.subList(fromIndex, toIndex).toList()
        entries.subList(fromIndex, toIndex).clear()
        entries.addAll(newIndex.coerceIn(0, entries.size), moved)
        index = entries.indexOfFirst { it.uid == old }.coerceAtLeast(0)
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val old = entries.getOrNull(index)?.uid
        entries.subList(fromIndex, toIndex).clear()
        val retained = entries.indexOfFirst { it.uid == old }
        index = if (entries.isEmpty()) 0 else if (retained >= 0) retained else fromIndex.coerceAtMost(entries.lastIndex)
        if (retained < 0) {
            position = 0
            if (entries.isEmpty()) handleStop() else if (prepared) requestLoad(0)
        }
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Metadata-only replacements preserve current decoder and position.
        if (toIndex - fromIndex == mediaItems.size && mediaItems.indices.all { offset ->
                entries[fromIndex + offset].item.localConfiguration?.uri == mediaItems[offset].localConfiguration?.uri
            }) {
            mediaItems.forEachIndexed { offset, item -> entries[fromIndex + offset].item = item }
        } else {
            val old = entries.getOrNull(index)?.uid
            entries.subList(fromIndex, toIndex).clear()
            entries.addAll(fromIndex, mediaItems.map { Entry(nextUid++, it) })
            val retained = entries.indexOfFirst { it.uid == old }
            index = if (entries.isEmpty()) 0 else if (retained >= 0) retained else fromIndex.coerceAtMost(entries.lastIndex)
            if (retained < 0) { position = 0; if (entries.isEmpty()) handleStop() else if (prepared) requestLoad(0) }
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (entries.isEmpty() || mediaItemIndex == C.INDEX_UNSET) return done()
        val target = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (mediaItemIndex != index || !prepared || playerError != null || playback == Player.STATE_ENDED) {
            index = mediaItemIndex.coerceIn(entries.indices)
            position = target
            if (prepared || playback != Player.STATE_IDLE) requestLoad(target)
        } else {
            position = target
            queueNative { checkResult(NativeFlick.seek(target / 1000.0)) }
        }
        return done()
    }

    private fun requestLoad(startMs: Long) {
        val entry = entries.getOrNull(index) ?: return
        val token = generation.incrementAndGet()
        usb.cancelPermission()
        prepared = true
        playback = Player.STATE_BUFFERING
        playerError = null
        position = startMs.coerceAtLeast(0)
        lastSpec = Tracks.EMPTY
        updateWakeLock()
        val uri = entry.item.localConfiguration?.uri ?: entry.item.requestMetadata.mediaUri ?: Uri.parse(entry.item.mediaId)
        val strictAtLoad = strictMode
        // Stop the previous source immediately, even while USB permission is pending.
        queueNative(token) { checkResult(NativeFlick.stop()) }
        val load = {
            if (!released && token == generation.get()) worker.execute {
                val result = runCatching {
                    if (token != generation.get()) return@runCatching JSONObject()
                    if (!nativeLoaded) {
                        check(NativeFlick.isAvailable) { "Rust 音訊程式庫未載入，請確認安裝的 APK 支援此裝置 ABI" }
                        checkResult(NativeFlick.initEngine(context)); nativeLoaded = true
                    }
                    checkResult(NativeFlick.stop())
                    if (strictAtLoad) usb.activateOnWorker() else usb.closeConnectionOnWorker()
                    val resolved = resolveUri(uri)
                    val auth = if (resolved != uri) emptyMap() else headers()
                    val resolvedString = if (resolved.scheme == "file") resolved.path ?: error("無效的本機檔案") else resolved.toString()
                    val metadata = checkResult(NativeFlick.open(resolvedString, JSONObject(auth).toString(), strictAtLoad, if (strictAtLoad) 1f else gain))
                    if (startMs > 0) checkResult(NativeFlick.seek(startMs / 1000.0))
                    if (desiredPlay && token == generation.get()) checkResult(NativeFlick.resume())
                    else checkResult(NativeFlick.pause())
                    if (token != generation.get()) NativeFlick.stop()
                    metadata
                }
                main.post {
                    if (!released && token == generation.get()) {
                        result.onSuccess { metadata ->
                            entry.durationMs = metadata.optLong("durationMs", C.TIME_UNSET).let { if (it > 0) it else C.TIME_UNSET }
                            lastSpec = tracks(metadata)
                            playback = Player.STATE_READY
                            updateWakeLock()
                            invalidateState()
                        }.onFailure { setFailure(it.message ?: "Rust 音訊載入失敗") }
                    }
                }
            }
        }
        // Permission is asynchronous, never block the Android main thread.
        if (strictAtLoad) usb.requestPermission { error ->
            if (token == generation.get() && !released) {
                if (error == null) load() else setFailure(error)
            }
        } else load()
        invalidateState()
    }

    private fun applySnapshot(snapshot: JSONObject) {
        val duration = snapshot.optLong("durationMs", C.TIME_UNSET)
        if (duration > 0) entries.getOrNull(index)?.durationMs = duration
        position = snapshot.optLong("positionMs", position).coerceAtLeast(0)
        val state = snapshot.optString("state")
        if (state == "error") { setFailure(snapshot.optString("error", "Rust 音訊輸出失敗")); return }
        if (state == "ended") {
            if (!desiredPlay) { playback = Player.STATE_ENDED; updateWakeLock(); invalidateState(); return }
            val next = when {
                repeat == Player.REPEAT_MODE_ONE -> index
                shuffle && entries.size > 1 -> entries.indices.filter { it != index }.random()
                index < entries.lastIndex -> index + 1
                repeat == Player.REPEAT_MODE_ALL && entries.isNotEmpty() -> 0
                else -> null
            }
            if (next == null) {
                desiredPlay = false
                playback = Player.STATE_ENDED
                abandonFocus()
            } else { index = next; position = 0; requestLoad(0) }
        }
        updateWakeLock()
        invalidateState()
    }

    private fun tracks(metadata: JSONObject): Tracks {
        val rate = metadata.optInt("sampleRate", -1)
        val channels = metadata.optInt("channels", -1)
        val bits = metadata.optInt("bitDepth", -1)
        val encoding = when (bits) { 16 -> C.ENCODING_PCM_16BIT; 24 -> C.ENCODING_PCM_24BIT; 32 -> C.ENCODING_PCM_32BIT; else -> Format.NO_VALUE }
        val format = Format.Builder().setId("flick").setSampleMimeType("audio/raw")
            .setSampleRate(rate).setChannelCount(channels).setPcmEncoding(encoding).build()
        return Tracks(listOf(Tracks.Group(TrackGroup("flick-audio", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
    }

    private fun queueNative(token: Int = generation.get(), operation: () -> Unit) {
        if (released) return
        worker.execute {
            if (token != generation.get()) return@execute
            val result = runCatching { if (nativeLoaded) operation() }
            result.exceptionOrNull()?.let { failure -> main.post {
                if (!released && token == generation.get()) setFailure(failure.message ?: "Rust 音訊操作失敗")
            } }
        }
    }

    private fun checkResult(json: String): JSONObject = JSONObject(json).also { result ->
        result.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { error(it) }
    }

    private fun setFailure(message: String) {
        // A detach/focus/permission error can race a native open already in flight.
        // Invalidate its completion so it cannot restore READY while an error is set.
        generation.incrementAndGet()
        usb.cancelPermission()
        desiredPlay = false
        playback = Player.STATE_IDLE
        prepared = false
        playerError = PlaybackException(message, null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        abandonFocus()
        updateWakeLock()
        invalidateState()
        queueNative { NativeFlick.stop(); usb.closeConnectionOnWorker() }
    }

    private fun onUsbDetached() {
        if (strictMode && !released) {
            pause()
            setFailure("USB DAC 已移除，bit-perfect 已停止；不會改用喇叭輸出")
            queueNative { checkResult(NativeFlick.stop()); usb.closeConnectionOnWorker() }
        }
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocus() {
        if (focusHeld) audioManager.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
    }

    private fun updateWakeLock() {
        val hold = !released && desiredPlay && (playback == Player.STATE_READY || playback == Player.STATE_BUFFERING)
        val now = android.os.SystemClock.elapsedRealtime()
        if (hold && (!wakeLock.isHeld || now - wakeRenewedAt > 5 * 60 * 1000L)) {
            wakeLock.acquire(10 * 60 * 1000L)
            wakeRenewedAt = now
        } else if (!hold && wakeLock.isHeld) wakeLock.release()
    }
}
