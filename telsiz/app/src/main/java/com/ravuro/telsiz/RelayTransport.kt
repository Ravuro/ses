package com.ravuro.telsiz

/**
 * İnternet yolu: aynı kanaldaki herkesi birbirine bağlayan WebSocket rölesi
 * (telsiz/server/). Bağlantı koparsa artan gecikmeyle kendi kendine yeniden
 * bağlanır; bu sırada LAN yolu çalışmaya devam ettiği için aynı ağdakiler
 * kesintiyi hissetmez.
 */
class RelayTransport(
    rawUrl: String,
    channel: Int,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onState: (String) -> Unit
) : RelayLink {
    private val url: String = normalize(rawUrl, channel)

    @Volatile private var running = false
    @Volatile private var ws: WebSocketClient? = null
    private var thread: Thread? = null

    @Volatile override var connected = false
        private set
    @Volatile override var status: String = "kapalı"
        private set

    @Volatile override var lastRxAt = 0L
        private set
    @Volatile private var revives = 0
    @Volatile private var drops = 0

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ supervise() }, "telsiz-relay").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        try { ws?.close() } catch (_: Exception) {}
        ws = null
        connected = false
        thread?.interrupt()
        thread = null
        setStatus("kapalı")
    }

    override fun send(data: ByteArray, len: Int) {
        if (!connected) return
        try {
            ws?.sendBinary(data, len)
        } catch (_: Exception) {
            // readLoop kopmayı zaten görecek ve yeniden bağlanacak.
        }
    }

    private fun supervise() {
        var backoff = 1000L
        while (running) {
            val client = WebSocketClient(url, object : WebSocketClient.Listener {
                override fun onBinary(data: ByteArray) {
                    lastRxAt = System.currentTimeMillis()
                    onPacket(data, data.size)
                }
            })
            try {
                setStatus("bağlanıyor")
                client.connect()
                ws = client
                connected = true
                backoff = 1000L
                setStatus("bağlı")
                lastRxAt = System.currentTimeMillis()
                client.readLoop()          // kopana kadar burada bekler
                drops++
                setStatus("koptu")
            } catch (e: Exception) {
                setStatus(shortError(e))
            } finally {
                connected = false
                try { client.close() } catch (_: Exception) {}
                if (ws === client) ws = null
            }
            if (!running) break
            if (!sleep(backoff)) break
            backoff = (backoff * 2).coerceAtMost(15000L)
        }
        setStatus("kapalı")
    }

    /**
     * Ölmüş bağlantıyı zorla kapatır; [supervise] kopmayı görüp yeniden
     * bağlanır. Bekçi, taşıyıcı canlı görünüp veri gelmediğinde çağırıyor.
     */
    override fun restart() {
        if (!running) return
        revives++
        connected = false
        try { ws?.close() } catch (_: Exception) {}
        ws = null
        if (thread?.isAlive != true) {
            thread = Thread({ supervise() }, "telsiz-relay").apply { isDaemon = true; start() }
        }
    }

    val alive: Boolean get() = thread?.isAlive == true

    override fun diag() = buildString {
        append("röle ").append(status)
        append(" · kopma ").append(drops)
        append(" · diriltme ").append(revives)
        append("\nson cevap ").append(
            if (lastRxAt == 0L) "hiç"
            else ((System.currentTimeMillis() - lastRxAt) / 1000).toString() + " sn önce"
        )
    }

    /** Beklerken durdurma isteğine hızlı cevap verebilmek için parçalı uyku. */
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
