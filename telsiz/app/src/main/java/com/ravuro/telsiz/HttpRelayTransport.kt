package com.ravuro.telsiz

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Paylaşımlı hosting (cPanel/PHP) için relay taşıyıcısı — `server/relay.php`.
 *
 * WebSocket yok, sürekli çalışan süreç yok: gönderilecek paketler ~200 ms'lik
 * gruplar halinde POST ediliyor, gelen ses ise uzun bekleyen bir GET ile
 * alınıyor (sunucu yeni veri gelene kadar cevabı tutuyor, boşuna sorgu
 * yapılmıyor).
 *
 * Bunun bedeli gecikme: gruplama + gidiş dönüş, kabaca yarım saniye. Telsizde
 * katlanılır; ses kalitesi etkilenmiyor, sadece geç geliyor.
 */
class HttpRelayTransport(
    baseUrl: String,
    private val channel: Int,
    deviceId: Long,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onState: (String) -> Unit
) : RelayLink {

    private companion object {
        const val BATCH_MS = 200L
        const val MAX_BATCH = 8192
        const val MAX_QUEUE = 50       // ~2 sn: bunun ötesi zaten geçersiz
        const val MAX_RESPONSE = 262144
    }

    private val base = baseUrl.trim().trimEnd('/')
    private val id = java.lang.Long.toHexString(deviceId)

    private val outbox = ArrayDeque<ByteArray>()
    private val outLock = Object()

    @Volatile private var running = false
    private var sendThread: Thread? = null
    private var pollThread: Thread? = null

    @Volatile override var connected = false
        private set
    @Volatile override var status = "kapalı"
        private set

    override fun start() {
        if (running) return
        running = true
        setStatus("bağlanıyor")
        sendThread = Thread({ sendLoop() }, "telsiz-http-tx").apply { isDaemon = true; start() }
        pollThread = Thread({ pollLoop() }, "telsiz-http-rx").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        connected = false
        sendThread?.interrupt()
        pollThread?.interrupt()
        sendThread = null
        pollThread = null
        synchronized(outLock) { outbox.clear() }
        setStatus("kapalı")
    }

    override fun send(data: ByteArray, len: Int) {
        if (!running) return
        val copy = data.copyOf(len)
        synchronized(outLock) {
            outbox.addLast(copy)
            // Geç kalmış ses zaten işe yaramaz; kuyruğu büyütmek yerine at.
            while (outbox.size > MAX_QUEUE) outbox.removeFirst()
        }
    }

    // ---- gönderim ----

    private fun sendLoop() {
        while (running) {
            try { Thread.sleep(BATCH_MS) } catch (_: InterruptedException) { return }
            if (!running) return

            val body = drain() ?: continue
            try {
                post(body)
            } catch (e: Exception) {
                connected = false
                setStatus(shortError(e))
            }
        }
    }

    /** Kuyruktaki paketleri tek gövdede birleştirir: [2 bayt uzunluk][paket]... */
    private fun drain(): ByteArray? {
        val out = ByteArrayOutputStream()
        synchronized(outLock) {
            while (outbox.isNotEmpty() && out.size() < MAX_BATCH) {
                val p = outbox.removeFirst()
                out.write((p.size shr 8) and 0xFF)
                out.write(p.size and 0xFF)
                out.write(p, 0, p.size)
            }
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    private fun post(body: ByteArray) {
        val c = open(url(null), "POST")
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("sunucu $code")
        } finally {
            c.disconnect()
        }
    }

    // ---- dinleme ----

    private fun pollLoop() {
        var cursor = -1L
        var backoff = 500L
        while (running) {
            try {
                val resp = get(cursor)
                if (!running) return
                connected = true
                setStatus("bağlı")
                backoff = 500L

                if (resp.size >= 8) {
                    cursor = readLe64(resp, 0)
                    deliver(resp, 8)
                }
            } catch (e: Exception) {
                if (!running) return
                connected = false
                setStatus(shortError(e))
                if (!sleep(backoff)) return
                backoff = (backoff * 2).coerceAtMost(10000L)
            }
        }
    }

    private fun get(cursor: Long): ByteArray {
        val c = open(url(cursor), "GET")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("sunucu $code")
            return readAll(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    /** Gövde: arka arkaya [2 bayt uzunluk][paket] kayıtları. */
    private fun deliver(buf: ByteArray, from: Int) {
        var off = from
        while (off + 2 <= buf.size) {
            val len = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
            off += 2
            if (len <= 0 || off + len > buf.size) return
            onPacket(buf.copyOfRange(off, off + len), len)
            off += len
        }
    }

    // ---- yardımcılar ----

    private fun url(cursor: Long?): String {
        val sep = if (base.contains('?')) '&' else '?'
        val sb = StringBuilder(base)
        sb.append(sep).append("ch=").append(channel).append("&id=").append(id)
        if (cursor != null && cursor >= 0) sb.append("&cur=").append(cursor)
        return sb.toString()
    }

    private fun open(u: String, method: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        // Sunucu cevabı 15 sn tutabiliyor; okuma zaman aşımı ondan uzun olmalı.
        c.readTimeout = 30000
        c.useCaches = false
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("User-Agent", "Telsiz")
        return c
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_RESPONSE) break
            }
        }
        return out.toByteArray()
    }

    private fun readLe64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun sleep(ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (running) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return true
            try { Thread.sleep(minOf(left, 250L)) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun setStatus(s: String) {
        status = s
        onState(s)
    }

    private fun shortError(e: Exception): String {
        val m = e.message
        return if (m.isNullOrBlank()) "bağlanamadı" else "hata: " + m.take(40)
    }
}
