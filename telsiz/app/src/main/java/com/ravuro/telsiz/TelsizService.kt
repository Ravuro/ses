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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

        /**
         * Röleden bu kadar süredir cevap yoksa bağlantı ölmüş sayılıyor.
         * Uzun bekleyen sorgu en geç 15 saniyede bir dönüyor, yani beş
         * turdan fazlası kaçmışsa soket gerçekten donmuş demektir.
         */
        private const val RELAY_STALE_MS = 75_000L

        /**
         * Aynı anda takip edilen en çok gönderen. Telsiz kanalında bu kadar
         * kişi zaten olmuyor; sınır, rastgele kimliklerle sel yaratan bozuk
         * ya da kötü niyetli bir kaynağın listeyi ve belleği şişirmesini
         * engelliyor.
         */
        private const val MAX_SENDERS = 32

        /** Ağdan gelen adın en çok kaç karakteri kullanılıyor. */
        private const val NICK_MAX = 24

        /** Tanımadığımız gönderenin penceresi bu kadar sessizlikten sonra silinir. */
        private const val WINDOW_TTL_MS = 60_000L

        @Volatile var isRunning = false

        /**
         * Erişilebilirlik servisi tuş olaylarını buraya veriyor. İkisi aynı
         * süreçte çalıştığı için doğrudan başvuru yeterli; servis dururken
         * temizleniyor.
         */
        @Volatile private var current: TelsizService? = null

        fun keyDown() {
            current?.startTx()
        }

        fun keyUp() {
            current?.stopTx()
        }

        /**
         * Nabız alarmının giriş kapısı. Servis ayaktaysa bileşenleri
         * denetletiyor; süreç öldürülmüşse ve kullanıcı telsizi kapatmadıysa
         * yeniden başlatıyor.
         */
        fun poke(ctx: Context) {
            val svc = current
            if (svc != null) {
                svc.heartbeat()
                return
            }
            if (!Prefs(ctx).sessionWanted) return
            try {
                ctx.startForegroundService(Intent(ctx, TelsizService::class.java))
            } catch (_: Exception) {
                // Arka planda servis başlatma kısıtlıysa yapacak bir şey yok;
                // bir sonraki nabızda yeniden denenecek.
            }
        }
    }

    /** Paketin hangi taşıyıcıdan geldiği — köprüleme için gerekli. */
    private enum class Source { LAN, RELAY }

    class Peer(val id: Long, nick: String) {
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
    private var keyPtt: KeyPtt? = null
    private var focusRequest: AudioFocusRequest? = null
    private var crypto: ChannelCrypto? = null

    private val peers = ConcurrentHashMap<Long, Peer>()
    /** Sesi çalınmayan kişiler. Yoklamaları geliyor, listede kalıyorlar. */
    private val muted = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val windows = ConcurrentHashMap<Long, SeqWindow>()

    private var deviceId: Long = 0
    private var channel: Int = 1
    private var nick: String = ""
    private var seq: Int = 0

    /** Tanı için: oturum ne zaman başladı, nabız kaç kez ne yaptı. */
    @Volatile var startedAt: Long = 0
    @Volatile var beats: Int = 0
    @Volatile var repairs: Int = 0
    @Volatile var lastRepair: String = "-"

    /**
     * Sessiz uyuşmazlıklar. İkisi de "kanalda kimse yok" gibi görünüyor ama
     * sebepleri bambaşka; kullanıcıya söylenmezse saatlerce aranıyor.
     */
    @Volatile var otherVersion = 0        // kanalda görülen farklı sürüm
    @Volatile var otherVersionAt = 0L
    @Volatile var wrongKeyAt = 0L         // çözülemeyen şifreli paket
    @Volatile var wrongKeyCount = 0
    @Volatile var plainOnSecureAt = 0L    // parolalı kanala şifresiz paket

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
    /** Röleye gidiş-dönüş süresi; ölçüm yoksa -1. */
    val relayRttMs: Int get() = (relay as? HttpRelayTransport)?.lastRttMs ?: -1

    /** Ekran kapalıyken ses tuşu dinlenebiliyor mu? */
    val keyPttReady: Boolean get() = keyPtt?.available == true

    /** Sisteme gelen ses tuşu olayı sayısı — yolun çalıştığının kanıtı. */
    val keyPttEvents: Int get() = keyPtt?.events ?: 0

    // ---- kaçırılanı tekrar dinleme ----

    val replayAvailable: Boolean get() = engine?.replayAvailable == true
    val replaySeconds: Int get() = engine?.replaySeconds ?: 0

    fun replayLast(): Boolean = engine?.replayLast() == true
    val transmitting: Boolean get() = engine?.transmitting == true

    private val txBuf = ByteArray(Packet.MAX)
    private val parsed = Packet.Parsed()
    /** Çözülen yük buraya açılıyor; onPacket zaten tek seferde çalışıyor. */
    private val plainBuf = ByteArray(Packet.MAX)

    /** Kanal parolalı mı? */
    val encrypted: Boolean get() = crypto != null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Kullanıcının kendi kararı: nabız bunu geri getirmesin.
            Prefs(this).sessionWanted = false
            Watchdog.disarm(this)
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

        // Anahtar türetme kasıtlı olarak yavaş; oturum başına bir kez.
        crypto = ChannelCrypto.derive(prefs.passphrase, channel)
        if (prefs.passphrase.isNotBlank() && crypto == null) {
            // Parola yazılmış ama anahtar türetilememiş. Sessizce şifresiz
            // devam etmek en kötüsü olurdu: kullanıcı korunduğunu sanırken
            // ses açıktan gider. Görünür bir hata bırakıyoruz — arayüzde
            // zaten ŞİFRESİZ yazacak, sebebi de belli olsun.
            startError = "parola anahtarı üretilemedi; ses şifresiz gidiyor"
        }
        // Sıra numarası sıfırdan başlamıyor: şifrelemede nonce buna bağlı.
        seq = prefs.nextSeqBase()

        // Bildirim her şeyden önce.
        //
        // startForegroundService ile başlatılan servis beş saniye içinde
        // startForeground çağırmak zorunda; çağırmadan durursa sistem
        // uygulamayı çökertiyor. İzin kontrolü bunun önüne geçerse —
        // kullanıcı mikrofon iznini sonradan geri aldığında ya da nabız
        // servisi izinsiz ayağa kaldırdığında tam olarak bu oluyor —
        // sözleşme ihlal edilmiş oluyor. Önce bildirimi kur, sonra dur.
        createNotificationChannel()
        startForegroundCompat()

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startError = "mikrofon izni verilmemiş"
            stopForegroundCompat()
            stopSelf()
            return
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telsiz:session").apply {
                setReferenceCounted(false)
                // Süre verilmiyor: dördüncü saatte sessizce düşüyordu ve
                // telsiz hiçbir hata göstermeden susuyordu. Servis
                // durdurulurken bırakılıyor.
                acquire()
            }
        } catch (_: Exception) {
        }

        val eng = AudioEngine { data, len, predictor, index ->
            broadcastAudio(data, len, predictor, index)
        }
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

        requestAudioFocus()

        // Ekran kapalıyken ses tuşunu duyabilmek için medya oturumu.
        if (prefs.volumePtt) {
            keyPtt = KeyPtt(this, { startTx() }, { stopTx() }).also { it.start() }
        }

        presenceThread = Thread({ presenceLoop() }, "telsiz-presence")
            .apply { isDaemon = true; start() }

        isRunning = true
        current = this
        startError = null
        startedAt = System.currentTimeMillis()
        // Kullanıcı DURDUR'a basana kadar telsiz açık sayılıyor: süreç
        // öldürülürse nabız buna bakıp geri getiriyor.
        prefs.sessionWanted = true
        Watchdog.arm(this)
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
        current = null
        presenceThread?.interrupt()
        presenceThread = null
        keyPtt?.stop()
        keyPtt = null
        abandonAudioFocus()
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

    // ---- sessize alma ----

    fun isMuted(id: Long): Boolean = muted.contains(id)

    fun toggleMute(id: Long): Boolean {
        val now = if (muted.contains(id)) {
            muted.remove(id); false
        } else {
            muted.add(id); true
        }
        // Sessize alınan kişinin biriken sesi çalınmasın.
        if (now) engine?.forget(id)
        return now
    }

    // ---- kişiye özel gönderim ----
    //
    // Bas-konuşa basıldığı anda hedef henüz belli değil: kullanıcı parmağını
    // sürükleyerek seçiyor. O aradaki sesi kaybetmemek ve yanlışlıkla
    // herkese göndermemek için kareler hedef belli olana kadar bekletiliyor,
    // sonra hepsi birden doğru hedefe gidiyor.

    private class PendingFrame(val data: ByteArray, val predictor: Int, val index: Int)

    private val pendingLock = Object()
    private val pending = ArrayList<PendingFrame>()
    @Volatile private var targetPending = false
    @Volatile private var currentTarget = 0L

    /** Konuşurken seçilen hedef; 0 = herkes. */
    val talkTarget: Long get() = currentTarget

    fun resolveTarget(target: Long) {
        val flush: List<PendingFrame>
        synchronized(pendingLock) {
            if (!targetPending) {
                currentTarget = target
                return
            }
            currentTarget = target
            targetPending = false
            flush = ArrayList(pending)
            pending.clear()
        }
        for (f in flush) sendPacket(Packet.TYPE_AUDIO, f.data, f.data.size, f.predictor, f.index)
    }

    // ---- bas-konuş ----

    /**
     * [pendingTarget] verilirse hedef seçilene kadar kareler bekletilir.
     * Ekrandaki halka seçiciden gelen basışlar böyle başlıyor; ses tuşu ve
     * kulaklık düğmesi doğrudan herkese gidiyor.
     */
    fun startTx(pendingTarget: Boolean = false) {
        val eng = engine ?: return
        if (eng.transmitting) return          // tuş ve dokunma aynı anda gelebilir
        synchronized(pendingLock) {
            pending.clear()
            targetPending = pendingTarget
            currentTarget = 0L
        }
        if (prefs.beep) sendBeep(Beep.START)
        eng.startTx()
        updateNotification()
    }

    fun stopTx() {
        val eng = engine ?: return
        if (!eng.transmitting) return
        eng.stopTx()
        // Hedef hiç seçilmediyse (parmak hemen kalktı) herkese gitsin.
        if (targetPending) resolveTarget(0L)
        // Bitiş bipi sesten SONRA gönderiliyor; alıcıda aynı kuyruğa
        // girdiği için kendiliğinden son sözün ardına düşüyor.
        if (prefs.beep) sendBeep(Beep.END)
        currentTarget = 0L
        updateNotification()
    }

    /**
     * Ses odağı. Yalnız nezaket değil: Android ses tuşlarını odağı elinde
     * tutan uygulamanın medya oturumuna yönlendiriyor, dolayısıyla ekran
     * kapalıyken bas-konuşun çalışması buna bağlı. Odak alındığında çalan
     * müzik duruyor — telsiz açıkken beklenen davranış.
     */
    private fun requestAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    private val beepPayload = ByteArray(1)

    private fun sendBeep(kind: Byte) {
        synchronized(beepPayload) {
            beepPayload[0] = kind
            sendPacket(Packet.TYPE_BEEP, beepPayload, 1)
        }
    }

    // ---- ağ ----

    private fun broadcastAudio(data: ByteArray, len: Int, predictor: Int, index: Int) {
        synchronized(pendingLock) {
            if (targetPending) {
                // Seçim uzarsa en eskiyi at: 2 saniyeden fazlası zaten geç.
                if (pending.size >= 50) pending.removeAt(0)
                pending.add(PendingFrame(data.copyOf(len), predictor, index))
                return
            }
        }
        sendPacket(Packet.TYPE_AUDIO, data, len, predictor, index)
    }

    private fun sendPresence() {
        val payload = nick.toByteArray(Charsets.UTF_8)
        sendPacket(Packet.TYPE_PRESENCE, payload, minOf(payload.size, 64))
    }

    /**
     * Ses ve yoklama ayrı thread'lerden geliyor; paylaşılan gönderim tamponu
     * kurulup gönderilene kadar tek parça olarak kilitli kalmalı.
     */
    private fun sendPacket(
        type: Byte,
        payload: ByteArray,
        len: Int,
        predictor: Int = 0,
        index: Int = 0
    ) {
        synchronized(txBuf) {
            val s = seq++
            val target = if (type == Packet.TYPE_AUDIO) currentTarget else 0L
            val c = crypto
            val total = if (c == null) {
                Packet.build(txBuf, type, channel, deviceId, s, payload, len, predictor, index, target)
            } else {
                // Başlık önce yazılıyor: şifrelemenin ek doğrulama verisi o.
                Packet.buildHeader(
                    txBuf, type, channel, deviceId, s,
                    len + ChannelCrypto.TAG_BYTES,
                    predictor, index, target, Packet.FLAG_ENCRYPTED
                )
                val n = c.seal(
                    txBuf, Packet.HEADER, payload, len, deviceId, s, txBuf, Packet.HEADER
                )
                Packet.HEADER + n
            }
            lan?.send(txBuf, total)
            relay?.send(txBuf, total)
        }
    }

    /** LAN ve relay ayrı thread'lerden çağırıyor; [parsed] paylaşılan durum. */
    @Synchronized
    private fun onPacket(buf: ByteArray, len: Int, from: Source) {
        if (!Packet.parse(buf, len, parsed)) {
            val v = Packet.foreignVersion(buf, len)
            if (v >= 0) {
                otherVersion = v
                otherVersionAt = System.currentTimeMillis()
            }
            return
        }
        if (parsed.senderId == deviceId) return          // kendi sesimiz
        if (parsed.channel != channel) return            // başka kanal
        // Kişiye özel: bize değilse çalmıyoruz. Bu gizlilik değil, nezaket —
        // paket yine herkese ulaşıyor.
        if (parsed.target != 0L && parsed.target != deviceId) return

        // Sel koruması. Yeni gönderen gelince yer yoksa en uzun süredir
        // sesi çıkmayan kayıt düşüyor.
        //
        // Yeni geleni reddetmek yanlış olurdu: rastgele kimlikler üreten bir
        // kaynak otuz iki yeri doldurup gerçek kişilerin duyulmasını
        // engellerdi. En eskiyi düşürmek bunu tersine çeviriyor — düzenli
        // yoklama gönderen gerçek kişi hep taze, sel kayıtları hep eski.
        if (!windows.containsKey(parsed.senderId)) evictIfFull()

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

        // Şifre çözme. Yanlış paroladan gelen ya da kurcalanmış paket
        // burada sessizce düşüyor.
        val c = crypto
        var data = buf
        var off = parsed.payloadOff
        var len2 = parsed.payloadLen
        if (parsed.encrypted) {
            if (c == null) {
                // Şifreli konuşuyorlar, bizim parolamız yok.
                wrongKeyAt = System.currentTimeMillis()
                wrongKeyCount++
                return
            }
            val n = c.open(
                buf, Packet.HEADER, buf, parsed.payloadOff, parsed.payloadLen,
                parsed.senderId, parsed.seq, plainBuf
            )
            if (n < 0) {
                // Paket şifreli ama bizim anahtarımızla açılmıyor: parola
                // (ya da davet kodu) farklı.
                wrongKeyAt = System.currentTimeMillis()
                wrongKeyCount++
                return
            }
            data = plainBuf
            off = 0
            len2 = n
        } else if (c != null) {
            // Parolalı kanalda şifresiz paket kabul edilmiyor: yoksa
            // şifrelemeyi devre dışı bırakmak için düz paket göndermek yeterdi.
            plainOnSecureAt = System.currentTimeMillis()
            return
        }

        val now = System.currentTimeMillis()
        when (parsed.type) {
            Packet.TYPE_AUDIO -> {
                peers.getOrPut(parsed.senderId) { Peer(parsed.senderId, "?") }.also {
                    it.lastSeen = now
                    it.lastAudio = now
                }
                if (!muted.contains(parsed.senderId)) {
                    engine?.enqueue(
                        parsed.senderId, data, off, len2,
                        parsed.predictor, parsed.index
                    )
                }
            }
            Packet.TYPE_BEEP -> {
                val kind = if (len2 > 0) data[off] else Beep.START
                val p = peers.getOrPut(parsed.senderId) { Peer(parsed.senderId, "?") }
                p.lastSeen = now
                // Başlangıç bipi konuşmanın habercisi; bitiş bipi değil.
                if (kind == Beep.START) p.lastAudio = now
                if (!muted.contains(parsed.senderId)) {
                    engine?.enqueuePcm(parsed.senderId, Beep.pcm(kind))
                }
            }
            Packet.TYPE_PRESENCE -> {
                val name = cleanNick(data, off, len2)
                val p = peers.getOrPut(parsed.senderId) { Peer(parsed.senderId, name) }
                p.nick = name
                p.lastSeen = now
            }
        }
    }

    /** En eski pencereyi düşürerek yeni gönderene yer açar. */
    private fun evictIfFull() {
        while (windows.size >= MAX_SENDERS) {
            var oldestId = 0L
            var oldestAt = Long.MAX_VALUE
            for (e in windows.entries) {
                if (e.value.touchedAt < oldestAt) {
                    oldestAt = e.value.touchedAt
                    oldestId = e.key
                }
            }
            // Boşalmışsa (başka bir iş parçacığı sildiyse) döngüden çık.
            if (windows.remove(oldestId) == null) return
            peers.remove(oldestId)
            engine?.forget(oldestId)
        }
    }

    /**
     * Ağdan gelen adı görüntülenebilir hâle getirir.
     *
     * Ad karşı taraftan geliyor, yani ona güvenilmez: uzunluğu da içeriği de
     * istenen her şey olabilir. Satır sonu ya da kontrol karakteri taşıyan
     * bir ad kişi listesini ve tanı metnini dağıtır, çok uzun olanı ekranı
     * taşırır.
     */
    private fun cleanNick(data: ByteArray, off: Int, len: Int): String {
        val raw = String(data, off, minOf(len, 128), Charsets.UTF_8)
        val sb = StringBuilder(NICK_MAX)
        for (ch in raw) {
            if (sb.length >= NICK_MAX) break
            // Kontrol karakterleri (satır sonu dahil) düşüyor.
            if (ch.code >= 32 && ch.code != 127) sb.append(ch)
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) "?" else out
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

    // ---- nabız ----
    //
    // Dakikada bir alarm çalıyor ve buraya geliyor. Buradaki her onarım
    // gerçek bir arızanın karşılığı; hiçbiri "olur da lazım olur" diye
    // yazılmadı.

    fun heartbeat() {
        beats++
        val fixes = StringBuilder()

        // Uyanıklık kilidi: dördüncü saatte kendiliğinden düştüğü görüldü.
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                fixes.append("kilit ")
            }
        } catch (_: Exception) {
        }

        // Ses motoru: mikrofonu başkası aldığında döngüler ölüyor.
        val eng = engine
        if (eng != null && !eng.alive) {
            try {
                eng.stop()
                if (eng.start()) fixes.append("ses ") else fixes.append("ses(olmadı) ")
            } catch (_: Exception) {
            }
        }

        // Yerel ağ: kendi bekçisi var ama iş parçacığı ölmüşse o da duruyor.
        val l = lan
        if (l != null) {
            if (!l.alive) {
                try { l.stop(); l.start(); fixes.append("yerel ") } catch (_: Exception) {}
            } else {
                l.kick()
            }
        }

        // Röle: asılı kalmış soketi ancak dışarıdan kapatmak kurtarıyor.
        val r = relay
        if (r != null) {
            val stale = r.lastRxAt != 0L &&
                System.currentTimeMillis() - r.lastRxAt > RELAY_STALE_MS
            val dead = when (r) {
                is HttpRelayTransport -> !r.alive
                is RelayTransport -> !r.alive
                else -> false
            }
            if (stale || dead) {
                try { r.restart(); fixes.append(if (dead) "röle(ölü) " else "röle(donmuş) ") }
                catch (_: Exception) {}
            }
        }

        // Yoklama: bu ölürse listede kimse görünmüyor ve karşı taraf da
        // bizi göremiyor.
        if (presenceThread?.isAlive != true) {
            presenceThread = Thread({ presenceLoop() }, "telsiz-presence")
                .apply { isDaemon = true; start() }
            fixes.append("yoklama ")
        }

        if (fixes.isNotEmpty()) {
            repairs++
            lastRepair = fixes.toString().trim()
            updateNotification()
        }
    }

    /**
     * Duyulmamanın sessiz sebebi varsa tek cümleyle söyler, yoksa null.
     * Son bir dakika içinde görülenler dikkate alınıyor: eski bir uyarı
     * düzeltildikten sonra ekranda kalmasın.
     */
    fun mismatchWarning(): String? {
        val now = System.currentTimeMillis()
        val fresh = 60_000L
        if (otherVersionAt != 0L && now - otherVersionAt < fresh) {
            return "Kanalda eski sürüm telsiz var (sürüm $otherVersion). " +
                "Birbirinizi duyamazsınız; o kişinin uygulamayı güncellemesi gerek."
        }
        if (wrongKeyAt != 0L && now - wrongKeyAt < fresh) {
            return "Kanalda konuşan var ama parolanız tutmuyor " +
                "($wrongKeyCount paket çözülemedi). Aynı davet kodunu kullandığınızdan emin olun."
        }
        if (plainOnSecureAt != 0L && now - plainOnSecureAt < fresh) {
            return "Kanalda parolasız konuşan var; siz parolalısınız. " +
                "Onu duyamazsınız — aynı davet kodunu kullanın."
        }
        return null
    }

    /** Tanı ekranının metni. Tahmin değil, ölçüm. */
    fun diagnostics(): String = buildString {
        val up = if (startedAt == 0L) 0 else (System.currentTimeMillis() - startedAt) / 1000
        append("açık ").append(up / 60).append(" dk ").append(up % 60).append(" sn")
        append(" · nabız ").append(beats)
        append(" · onarım ").append(repairs)
        if (lastRepair != "-") append("\nson onarım: ").append(lastRepair)
        append("\nses ").append(if (engine?.alive == true) "çalışıyor" else "DURMUŞ")
        val l = lan
        append(" · yerel ").append(if (l?.alive == true) "açık" else "DURMUŞ")
        if (l != null) append(" (").append(l.localIp).append(", kurulum ").append(l.rebuilds).append(")")
        append("\n").append(relay?.diag() ?: "röle ayarlı değil")
        append("\nkanaldaki kişi ").append(peers.size)
        mismatchWarning()?.let { append("\nUYUŞMAZLIK: ").append(it) }
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

        // Pencereleri ayrıca yaşlarına göre süpür.
        //
        // Her pencere bir kişiye karşılık gelmiyor: şifresini çözemediğimiz
        // paketler de tekilleştirmeden geçiyor ve pencere açıyor. Aynı kanal
        // numarasını farklı parolayla kullanan bir grup varsa o kayıtlar
        // [peers] içine hiç girmiyor, dolayısıyla yukarıdaki temizlik onlara
        // dokunmuyordu ve liste sessizce büyüyordu.
        val wi = windows.entries.iterator()
        while (wi.hasNext()) {
            val e = wi.next()
            if (now - e.value.touchedAt > WINDOW_TTL_MS) wi.remove()
        }
    }

    // ---- arayüz için durum ----

    fun peerList(): List<Peer> = peers.values.sortedBy { it.nick.lowercase() }

    fun talkingNow(): List<String> {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastAudio < 700 }.map { it.nick }
    }

    /**
     * Şu an konuşanların kimlikleri.
     *
     * Arayüz eskiden ada bakıyordu; aynı adı taşıyan iki kişi olduğunda
     * (ki "Ali" hiç de nadir değil) biri konuşurken ikisi birden konuşuyor
     * görünüyordu. Ad kullanıcının yazdığı şey, kimlik ise kurulum başına
     * benzersiz.
     */
    fun talkingIds(): Set<Long> {
        val now = System.currentTimeMillis()
        val out = HashSet<Long>()
        for (p in peers.values) if (now - p.lastAudio < 700) out.add(p.id)
        return out
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
