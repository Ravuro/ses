package com.ravuro.telsiz

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * İnternet yolu: aynı kanaldaki herkesi birbirine bağlayan basit bir
 * WebSocket relay'i (telsiz/server/). Bağlantı koparsa artan gecikmeyle
 * kendi kendine yeniden bağlanır — LAN yolu bu sırada çalışmaya devam eder.
 */
class RelayTransport(
    rawUrl: String,
    private val channel: Int,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onState: (String) -> Unit
) {
    private val url: String = normalize(rawUrl, channel)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false
        private set
    @Volatile var status: String = "kapalı"
        private set

    private var backoffMs = 1000L
    private var thread: Thread? = null
    private val lock = Object()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ supervise() }, "telsiz-relay").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        connected = false
        setStatus("kapalı")
        synchronized(lock) { lock.notifyAll() }
        thread?.interrupt()
        thread = null
    }

    fun send(data: ByteArray, len: Int) {
        val socket = ws ?: return
        if (!connected) return
        try {
            socket.send(data.copyOf(len).toByteString())
        } catch (_: Exception) {
        }
    }

    private fun supervise() {
        var first = true
        while (running) {
            if (!first) {
                // Denemeler arası bekleme burada: aksi halde onFailure'ın
                // uyandırması yüzünden hiç beklemeden yeniden denerdik.
                if (!sleep(backoffMs)) return
            }
            first = false

            connected = false
            setStatus("bağlanıyor")
            connect()

            // Sonucu bekle — onOpen/onFailure uyandırıyor.
            synchronized(lock) {
                try { lock.wait(15000) } catch (_: InterruptedException) { return }
            }
            if (!running) return

            if (connected) {
                backoffMs = 1000L
                // Kopana kadar uyu.
                synchronized(lock) {
                    while (running && connected) {
                        try { lock.wait(5000) } catch (_: InterruptedException) { return }
                    }
                }
            } else {
                backoffMs = (backoffMs * 2).coerceAtMost(15000L)
            }
        }
    }

    /** Beklemeyi bölerek durdurma isteğine hızlı cevap verir. */
    private fun sleep(ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (running) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return true
            try { Thread.sleep(minOf(left, 250L)) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun connect() {
        try { ws?.cancel() } catch (_: Exception) {}
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                setStatus("bağlı")
                synchronized(lock) { lock.notifyAll() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                onPacket(arr, arr.size)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                setStatus("hata: " + (t.message ?: "bağlanamadı"))
                synchronized(lock) { lock.notifyAll() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                setStatus("kapandı")
                synchronized(lock) { lock.notifyAll() }
            }
        })
    }

    private fun setStatus(s: String) {
        status = s
        onState(s)
    }

    private fun normalize(raw: String, ch: Int): String {
        var u = raw.trim()
        if (u.startsWith("https://")) u = "wss://" + u.substring(8)
        else if (u.startsWith("http://")) u = "ws://" + u.substring(7)
        else if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "wss://$u"
        u = u.trimEnd('/')
        val sep = if (u.contains("?")) "&" else "?"
        return "$u${sep}ch=$ch"
    }
}
