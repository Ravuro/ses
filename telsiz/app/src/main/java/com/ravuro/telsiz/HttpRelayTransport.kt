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

        /** POST'un uzun bekleme hakkı yok: gönderim ya hızlı olur ya olmaz. */
        const val POST_READ_MS = 10000

        /** Sunucu cevabı 15 sn tutabiliyor; GET'in okuma payı ondan uzun. */
        const val POLL_READ_MS = 30000
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

    /** Son POST'un gidiş-dönüş süresi. Uydurma değil: arayüzde bunu gösteriyoruz. */
    @Volatile var lastRttMs: Int = -1
        private set

    @Volatile override var lastRxAt = 0L
        private set
    @Volatile private var lastTxAt = 0L
    @Volatile private var polls = 0
    @Volatile private var pollFails = 0
    @Volatile private var postFails = 0
    @Volatile private var revives = 0
    @Volatile private var lastFault: String = "-"

    /**
     * Sunucunun bildirdiği bekleme kipi: "uzun" normal, "kalabalik" sınıra
     * takıldı, "kilitsiz" sunucuda kilit mekanizması yok. Sorgu sayısının
     * neden yüksek olduğunu tahmin etmek yerine sormak için.
     */
    @Volatile private var waitMode: String = "-"

    /**
     * Kaçıncı kuruluşta olduğumuz. Asılı bir soket okuması [Thread.interrupt]
     * ile kesilmiyor; eski iş parçacığı zaman aşımına kadar yaşıyor. Kuşak
     * numarası eskiyi kendiliğinden emekliye ayırıyor, yoksa iki dinleyici
     * aynı anda sorgulamaya başlıyor.
     */
    @Volatile private var generation = 0

    /** Açık duran uzun sorgu; kesmenin tek yolu soketi kapatmak. */
    @Volatile private var pollConn: HttpURLConnection? = null

    override fun start() {
        if (running) return
        running = true
        lastRxAt = 0L
        setStatus("bağlanıyor")
        spawn()
    }

    private fun spawn() {
        val g = ++generation
        sendThread = Thread({ guard("tx") { sendLoop(g) } }, "telsiz-http-tx")
            .apply { isDaemon = true; start() }
        pollThread = Thread({ guard("rx") { pollLoop(g) } }, "telsiz-http-rx")
            .apply { isDaemon = true; start() }
    }

    /** Bekçi buna bakıp taşıyıcının gerçekten canlı olup olmadığına karar veriyor. */
    val alive: Boolean
        get() = sendThread?.isAlive == true && pollThread?.isAlive == true

    /**
     * Bir iş parçacığı beklenmedik bir şeyle ölürse telsiz hiçbir uyarı
     * vermeden sağırlaşıyordu: bayrak "bağlı" kalıyor, kimse konuşmuyor
     * sanılıyor. Ölüm sebebi kaydediliyor ve bekçi tekrar kuruyor.
     */
    private fun guard(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            lastFault = name + ": " + (t.message ?: t.javaClass.simpleName)
            connected = false
        }
    }

    /**
     * Ölmüş ya da asılı kalmış bağlantıyı sıfırdan kurar. Asılı okumayı
     * kesmenin tek yolu iş parçacığını bölmek: soket kapanınca okuma
     * hatayla düşüyor.
     */
    override fun restart() {
        if (!running) return
        revives++
        connected = false
        setStatus("yeniden kuruluyor")
        cut()
        synchronized(outLock) { outbox.clear() }
        spawn()
    }

    /** Kuşağı ilerletip açık soketi kapatır: asılı okuma anında düşer. */
    private fun cut() {
        generation++
        try { pollConn?.disconnect() } catch (_: Exception) {}
        pollConn = null
        sendThread?.interrupt()
        pollThread?.interrupt()
        sendThread = null
        pollThread = null
    }

    override fun diag() = buildString {
        append("röle ").append(status)
        append(" · sorgu ").append(polls)
        append(" · hata ").append(pollFails).append("/").append(postFails)
        append(" · diriltme ").append(revives)
        append(" · gecikme ").append(if (lastRttMs < 0) "-" else lastRttMs.toString() + "ms")
        append(" · bekleme ").append(waitMode)
        append("\nson cevap ").append(agoText(lastRxAt))
        append(" · son gönderim ").append(agoText(lastTxAt))
        if (lastFault != "-") append("\nson arıza: ").append(lastFault)
    }

    private fun agoText(at: Long): String {
        if (at == 0L) return "hiç"
        return ((System.currentTimeMillis() - at) / 1000).toString() + " sn önce"
    }

    override fun stop() {
        running = false
        connected = false
        cut()
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

    private fun sendLoop(gen: Int) {
        while (running && gen == generation) {
            try { Thread.sleep(BATCH_MS) } catch (_: InterruptedException) { return }
            if (!running || gen != generation) return

            val body = drain() ?: continue
            try {
                val t0 = System.currentTimeMillis()
                post(body)
                lastRttMs = (System.currentTimeMillis() - t0).toInt()
                lastTxAt = System.currentTimeMillis()
            } catch (e: Exception) {
                postFails++
                lastFault = "gönderim: " + shortError(e)
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
        val c = open(url(null), "POST", POST_READ_MS)
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

    private fun pollLoop(gen: Int) {
        var cursor = -1L
        var backoff = 500L
        while (running && gen == generation) {
            try {
                polls++
                val resp = get(cursor, gen)
                if (!running || gen != generation) return
                lastRxAt = System.currentTimeMillis()
                connected = true
                setStatus("bağlı")
                backoff = 500L

                if (resp.size >= 8) {
                    cursor = readLe64(resp, 0)
                    deliver(resp, 8)
                }
            } catch (e: Exception) {
                if (!running || gen != generation) return
                pollFails++
                lastFault = "dinleme: " + shortError(e)
                connected = false
                setStatus(shortError(e))
                if (!sleep(backoff)) return
                backoff = (backoff * 2).coerceAtMost(10000L)
            }
        }
    }

    private fun get(cursor: Long, gen: Int): ByteArray {
        val c = open(url(cursor), "GET", POLL_READ_MS)
        // Bekçi bu soketi kapatarak asılı okumayı kesebilsin.
        pollConn = c
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("sunucu $code")
            c.getHeaderField("X-Telsiz-Bekleme")?.let { waitMode = it }
            return readAll(c.inputStream)
        } finally {
            if (gen == generation) pollConn = null
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

    private fun open(u: String, method: String, readMs: Int): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = readMs
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
