package com.ravuro.telsiz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Telsiz oturumunu taşıyan servis. Uygulama arka plandayken ya da ekran
 * kapalıyken de dinlemeye devam etmesi gerektiği için foreground servis.
 *
 * Ses hem LAN'a hem relay'e aynı anda gönderilir; gelen tarafta ikisi de
 * dinlenir ve kopyalar sıra numarasıyla elenir. Yani "şebeke yoksa hotspot
 * devreye girsin" için kullanıcının bir şey seçmesi gerekmiyor — hangi yol
 * ayaktaysa ses oradan geçiyor.
 */
class TelsizService : Service() {

    companion object {
        const val ACTION_STOP = "com.ravuro.telsiz.STOP"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "telsiz_durum"
        private const val PRESENCE_MS = 3000L
        private const val PEER_TTL_MS = 12000L

        @Volatile var isRunning = false
    }

    /** Paketin hangi taşıyıcıdan geldiği — köprüleme için gerekli. */
    private enum class Source { LAN, RELAY }

    class Peer(nick: String) {
        @Volatile var nick: String = nick
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        @Volatile var lastAudio: Long = 0
    }

    inner class LocalBinder : Binder() {
        val service: TelsizService get() = this@TelsizService
    }

    private val binder = LocalBinder()

    private lateinit var prefs: Prefs
    private var engine: AudioEngine? = null
    private var lan: LanTransport? = null
    private var relay: RelayLink? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var presenceThread: Thread? = null
    private var direct: WifiDirect? = null

    private val peers = ConcurrentHashMap<Long, Peer>()
    private val windows = ConcurrentHashMap<Long, SeqWindow>()

    private var deviceId: Long = 0
    private var channel: Int = 1
    private var nick: String = ""
    private var seq: Int = 0

    @Volatile var relayStatus: String = "kapalı"
        private set
    @Volatile var startError: String? = null
        private set
    /** Köprülenen paket sayısı — arayüzde köprünün gerçekten aktığını göstermek için. */
    @Volatile var bridged: Long = 0
        private set

    /** İki yol da ayakta: bu cihaz yerel grup ile interneti birbirine bağlıyor. */
    val bridging: Boolean get() = relayConnected && lanError == null

    val lanIp: String get() = lan?.localIp ?: "-"
    val lanError: String? get() = lan?.lastError
    val lanRebuilds: Int get() = lan?.rebuilds ?: 0

    // ---- altyapısız ağ ----

    val directState: String get() = direct?.state ?: "kapalı"
    val directSsid: String? get() = direct?.ssid
    val directPassphrase: String? get() = direct?.passphrase
    val directClients: Int get() = direct?.clientCount ?: 0
    val directActive: Boolean get() = direct?.active == true
    fun directSupported(): Boolean = direct?.supported() == true

    fun createDirectGroup() {
        direct?.createGroup()
    }

    fun removeDirectGroup() {
        direct?.removeGroup()
    }
    val relayEnabled: Boolean get() = relay != null
    val relayConnected: Boolean get() = relay?.connected == true
    val transmitting: Boolean get() = engine?.transmitting == true

    private val txBuf = ByteArray(Packet.MAX)
    private val parsed = Packet.Parsed()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) startSession()
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    // ---- oturum ----

    private fun startSession() {
        prefs = Prefs(this)
        deviceId = prefs.deviceId
        channel = prefs.channel.coerceIn(1, 999)
        nick = prefs.nick.ifBlank { "Telsiz-" + (deviceId and 0xFFF).toString(16) }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Android 14+ mikrofon tipli foreground servisi izinsiz başlatınca
            // SecurityException atıyor; çökmek yerine hatayı bildir.
            startError = "mikrofon izni verilmemiş"
            stopSelf()
            return
        }

        createNotificationChannel()
        startForegroundCompat()

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telsiz:session").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }

        val eng = AudioEngine { data, len -> broadcastAudio(data, len) }
        if (!eng.start()) {
            startError = eng.lastError ?: "ses başlatılamadı"
            stopForegroundCompat()
            stopSelf()
            return
        }
        engine = eng

        lan = LanTransport(this) { buf, len -> onPacket(buf, len, Source.LAN) }.also { it.start() }

        // Grup kurulunca yeni bir ağ arayüzü doğuyor; LanTransport'un onu
        // beklemeden yakalaması için dürtüyoruz.
        direct = WifiDirect(this) {
            lan?.kick()
            updateNotification()
        }

        val url = prefs.relayUrl.trim()
        relay = if (url.isNotEmpty()) {
            createRelay(url).also { it.start() }
        } else {
            relayStatus = "ayarlı değil"
            null
        }

        presenceThread = Thread({ presenceLoop() }, "telsiz-presence")
            .apply { isDaemon = true; start() }

        isRunning = true
        startError = null
        updateNotification()
    }

    /**
     * Adresin şeması taşıyıcıyı belirliyor: http(s) verilirse paylaşımlı
     * hosting'deki PHP rölesi, ws(s) verilirse WebSocket sunucusu.
     */
    private fun createRelay(url: String): RelayLink {
        val onPkt: (ByteArray, Int) -> Unit = { buf, len -> onPacket(buf, len, Source.RELAY) }
        val onState: (String) -> Unit = { s ->
            relayStatus = s
            updateNotification()
        }
        val lower = url.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) {
            HttpRelayTransport(url, channel, deviceId, onPkt, onState)
        } else {
            RelayTransport(url, channel, onPkt, onState)
        }
    }

    private fun stopSession() {
        isRunning = false
        presenceThread?.interrupt()
        presenceThread = null
        engine?.stop()
        engine = null
        direct?.stop()
        direct = null
        lan?.stop()
        lan = null
        relay?.stop()
        relay = null
        peers.clear()
        windows.clear()
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        stopForegroundCompat()
    }

    // ---- bas-konuş ----

    fun startTx() {
        engine?.startTx()
        updateNotification()
    }

    fun stopTx() {
        engine?.stopTx()
        updateNotification()
    }

    // ---- ağ ----

    private fun broadcastAudio(data: ByteArray, len: Int) {
        sendPacket(Packet.TYPE_AUDIO, data, len)
    }

    private fun sendPresence() {
        val payload = nick.toByteArray(Charsets.UTF_8)
        sendPacket(Packet.TYPE_PRESENCE, payload, minOf(payload.size, 64))
    }

    /**
     * Ses ve yoklama ayrı thread'lerden geliyor; paylaşılan gönderim tamponu
     * kurulup gönderilene kadar tek parça olarak kilitli kalmalı.
     */
    private fun sendPacket(type: Byte, payload: ByteArray, len: Int) {
        synchronized(txBuf) {
            val total = Packet.build(txBuf, type, channel, deviceId, seq++, payload, len)
            lan?.send(txBuf, total)
            relay?.send(txBuf, total)
        }
    }

    /** LAN ve relay ayrı thread'lerden çağırıyor; [parsed] paylaşılan durum. */
    @Synchronized
    private fun onPacket(buf: ByteArray, len: Int, from: Source) {
        if (!Packet.parse(buf, len, parsed)) return
        if (parsed.senderId == deviceId) return          // kendi sesimiz
        if (parsed.channel != channel) return            // başka kanal

        // Aynı paket hem LAN'dan hem relay'den gelebilir.
        val w = windows.getOrPut(parsed.senderId) { SeqWindow() }
        val fresh = synchronized(w) { w.accept(parsed.seq) }
        if (!fresh) return

        // Köprü: iki yola da bağlıysak, birinden geleni diğerine aktarıyoruz.
        // Böylece internetsiz bir Wi-Fi Direct grubundaki herkes, aralarında
        // şebekesi olan tek bir kişi üzerinden dışarıyla konuşabiliyor —
        // menzili uzatmanın donanım gerektirmeyen tek gerçek yolu bu.
        //
        // Döngü olmuyor: aktarma tekilleştirmeden sonra yapılıyor, yani bir
        // cihaz aynı paketi en fazla bir kez aktarıyor. İki köprü varsa paket
        // karşı tarafa iki kez düşer, o da alıcıda eleniyor.
        forward(buf, len, from)

        val now = System.currentTimeMillis()
        when (parsed.type) {
            Packet.TYPE_AUDIO -> {
                peers.getOrPut(parsed.senderId) { Peer("?") }.also {
                    it.lastSeen = now
                    it.lastAudio = now
                }
                engine?.enqueue(parsed.senderId, buf, parsed.payloadOff, parsed.payloadLen)
            }
            Packet.TYPE_PRESENCE -> {
                val name = String(buf, parsed.payloadOff, parsed.payloadLen, Charsets.UTF_8)
                val p = peers.getOrPut(parsed.senderId) { Peer(name) }
                p.nick = name
                p.lastSeen = now
            }
        }
    }

    private fun forward(buf: ByteArray, len: Int, from: Source) {
        when (from) {
            Source.LAN -> {
                val r = relay ?: return
                if (!r.connected) return
                r.send(buf, len)
            }
            Source.RELAY -> lan?.send(buf, len)
        }
        bridged++
    }

    private fun presenceLoop() {
        while (isRunning || engine != null) {
            try {
                sendPresence()
                expirePeers()
            } catch (_: Exception) {
            }
            try { Thread.sleep(PRESENCE_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun expirePeers() {
        val now = System.currentTimeMillis()
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > PEER_TTL_MS) {
                engine?.forget(e.key)
                windows.remove(e.key)
                it.remove()
            }
        }
    }

    // ---- arayüz için durum ----

    fun peerList(): List<Peer> = peers.values.sortedBy { it.nick.lowercase() }

    fun talkingNow(): List<String> {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastAudio < 700 }.map { it.nick }
    }

    fun currentChannel(): Int = channel
    fun currentNick(): String = nick

    // ---- bildirim ----

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Telsiz durumu", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TelsizService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val line = buildString {
            append("Kanal ").append(channel)
            when {
                directActive -> append(" · Telsiz ağı açık")
                lanError == null -> append(" · Yerel ağ")
                else -> append(" · Ağ yok")
            }
            if (relayEnabled) append(" · Relay ").append(if (relayConnected) "bağlı" else relayStatus)
            if (bridging) append(" · köprü")
        }

        // Uygulama kaynak dosyası olmadan derlendiği için kendi ikonumuz yok;
        // çerçevenin mikrofon ikonunu kullanıyoruz.
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (transmitting) "Konuşuyorsun" else "Telsiz açık")
            .setContentText(line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", stop)
            .build()
    }

    private fun startForegroundCompat() {
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun updateNotification() {
        if (!isRunning) return
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
