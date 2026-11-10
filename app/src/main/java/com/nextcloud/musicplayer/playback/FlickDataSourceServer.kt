package com.nextcloud.musicplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only byte bridge: Rust reads Range HTTP, while Media3 keeps ownership of
 * authentication, indexed cache spans, file/content URIs and cache eviction.
 * Tokens are random and never contain the upstream URL or its credentials.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FlickDataSourceServer(private val factory: DataSource.Factory) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sources = ConcurrentHashMap<String, Uri>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "FlickCacheRead").apply { isDaemon = true }
    }
    private val acceptor = Thread({
        while (!closed.get()) {
            try {
                val socket = server.accept()
                socket.soTimeout = 30_000
                clients.add(socket)
                workers.execute { serve(socket) }
            } catch (_: Exception) {
                if (!closed.get()) continue else break
            }
        }
    }, "FlickCacheServer").apply { isDaemon = true; start() }

    fun resolve(uri: Uri): Uri {
        check(!closed.get()) { "音訊資料來源已關閉" }
        val key = UUID.randomUUID().toString()
        sources[key] = uri
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
        return Uri.parse("http://127.0.0.1:${server.localPort}/$key/track.$extension")
    }

    private fun serve(socket: Socket) {
        var dataSource: DataSource? = null
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                fun line(): String {
                    val out = StringBuilder()
                    while (out.length < 8192) {
                        val char = input.read()
                        if (char < 0 || char == 10) return out.toString().trimEnd('\r')
                        out.append(char.toChar())
                    }
                    throw IllegalArgumentException("header too long")
                }
                val request = line().split(' ')
                require(request.size == 3 && request[0] in setOf("GET", "HEAD"))
                val token = request[1].substringBefore('?').split('/').getOrNull(1)
                val uri = sources[token] ?: run {
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                var range: String? = null
                var headerBytes = 0
                while (true) {
                    val next = line()
                    headerBytes += next.length
                    require(headerBytes < 32768)
                    if (next.isEmpty()) break
                    if (next.startsWith("Range:", ignoreCase = true)) range = next.substringAfter(':').trim()
                }
                val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                require(range == null || match != null)
                val start = match?.groupValues?.get(1)?.toLong() ?: 0L
                val requestedEnd = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLong()
                require(requestedEnd == null || requestedEnd >= start)
                val source = factory.createDataSource()
                dataSource = source
                // An unbounded open reports bytes remaining, unlike a requested-length open.
                val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
                val total = if (remaining != C.LENGTH_UNSET.toLong()) remaining + start else null
                val count = when {
                    requestedEnd != null && remaining >= 0 -> minOf(requestedEnd - start + 1, remaining)
                    requestedEnd != null -> requestedEnd - start + 1
                    remaining >= 0 -> remaining
                    else -> null
                }
                val output = socket.getOutputStream()
                val response = buildString {
                    append(if (match != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Accept-Ranges: bytes\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n")
                    if (count != null) append("Content-Length: $count\r\n")
                    if (match != null && count != null && count > 0) append("Content-Range: bytes $start-${start + count - 1}/${total ?: "*"}\r\n")
                    append("\r\n")
                }
                output.write(response.toByteArray(Charsets.US_ASCII))
                if (request[0] == "HEAD") return
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (!closed.get() && (count == null || copied < count)) {
                    val wanted = if (count == null) buffer.size else minOf(buffer.size.toLong(), count - copied).toInt()
                    val size = source.read(buffer, 0, wanted)
                    if (size == C.RESULT_END_OF_INPUT) break
                    if (size == 0) continue
                    output.write(buffer, 0, size)
                    copied += size
                }
                output.flush()
            }
        } catch (_: Exception) {
            // Errors surface as interrupted HTTP reads; do not log authenticated URIs.
            runCatching { socket.close() }
        } finally {
            runCatching { dataSource?.close() }
            clients.remove(socket)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        sources.clear()
    }
}
