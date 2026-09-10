package com.ravuro.telsiz

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Şebeke gerekmeyen yol: aynı yerel ağdaki cihazlar birbirini doğrudan duyar.
 * Bu ağ bir router, bir hotspot ya da bir Wi-Fi Direct grubu olabilir — hepsi
 * bu taşıyıcı için sadece bir arayüz.
 *
 * Hem multicast grubuna hem de her arayüzün yayın (broadcast) adresine
 * gönderiyoruz: bazı router'lar ve çoğu hotspot multicast'i düşürürken subnet
 * broadcast'i geçiriyor, bazılarında tersi oluyor. Kopyalar sıra numarasıyla
 * zaten eleniyor.
 *
 * Ağ değişimi: telefon sürekli ağ değiştiriyor — WiFi kopuyor, hotspot
 * açılıyor, Wi-Fi Direct grubu kuruluyor. Soketi bir kez açıp bırakmak
 * yetmiyor; multicast üyeliği eski arayüze bağlı kalıyor ve ağ geri gelse
 * bile ses akmıyor. Bu yüzden arayüz listesi düzenli olarak izleniyor ve
 * değiştiği anda soket kapatılıp yeniden kuruluyor.
 */
class LanTransport(
    private val ctx: Context,
    private val onPacket: (ByteArray, Int) -> Unit
) {
    companion object {
        const val PORT = 47771
        const val GROUP = "239.255.42.99"
        private const val WATCH_MS = 2500L
        /** Multicast üyeliğini bu aralıkla tazele. */
        private const val REJOIN_MS = 30_000L
        /**
         * Bu kadar süre hiçbir paket gelmezse soket ölmüş sayılır.
         *
         * Kendi yoklama paketlerimizi de duyuyoruz (yayın adresine
         * gönderdiğimiz paket aynı sokete geri düşüyor) ve bunlar 3 saniyede
         * bir gidiyor. Yani sessizlik "kimse konuşmuyor" değil, "soket artık
         * dinlemiyor" demek.
         */
        private const val DEAD_MS = 90_000L
    }

    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var rxThread: Thread? = null
    private var watchThread: Thread? = null

    @Volatile private var targets: List<InetAddress> = emptyList()
    @Volatile private var signature: String = ""

    @Volatile var localIp: String = "-"
        private set
    @Volatile var lastError: String? = null
        private set
    /** Kaç kez yeniden kurulduğu — arayüzde ağ oynaklığını göstermek için. */
    @Volatile var rebuilds: Int = 0
        private set

    @Volatile private var lastRxAt = 0L
    private var lastRejoinAt = 0L

    fun start() {
        if (running) return
        running = true
        lastRxAt = System.currentTimeMillis()
        lastRejoinAt = lastRxAt
        acquireLock()
        scanInterfaces(force = true)

        rxThread = Thread({ receiveLoop() }, "telsiz-lan-rx").apply { isDaemon = true; start() }
        watchThread = Thread({ watchLoop() }, "telsiz-lan-net").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        watchThread?.interrupt()
        rxThread?.interrupt()
        closeSocket()
        watchThread = null
        rxThread = null
        releaseLock()
    }

    fun send(data: ByteArray, len: Int) {
        val s = socket ?: return
        for (addr in targets) {
            try {
                s.send(DatagramPacket(data, 0, len, addr, PORT))
            } catch (_: Exception) {
                // Bir arayüz kapanmış olabilir; diğerlerine göndermeye devam.
                // Kalıcıysa izleyici zaten soketi yeniden kuracak.
            }
        }
    }

    /** Wi-Fi Direct grubu kurulduğunda arayüzü beklemeden yakalamak için. */
    fun kick() {
        if (running) scanInterfaces(force = false)
    }

    // ---- soket yaşam döngüsü ----

    private fun openSocket(): Boolean {
        closeSocket()
        return try {
            // MulticastSocket kurucusu SO_REUSEADDR'ı zaten açıyor.
            val s = MulticastSocket(PORT)
            s.broadcast = true
            s.timeToLive = 1
            s.soTimeout = 1000
            joinOnAllInterfaces(s)
            socket = s
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message ?: "soket açılamadı"
            socket = null
            false
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Exception) {}
    }

    private fun joinOnAllInterfaces(s: MulticastSocket) {
        val group = InetAddress.getByName(GROUP)
        var joined = false
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                try {
                    if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                    s.joinGroup(InetSocketAddress(group, PORT), ni)
                    joined = true
                } catch (_: Exception) {
                    // Bu arayüz multicast'e izin vermiyor; broadcast yolu duruyor.
                }
            }
        } catch (_: Exception) {
        }
        if (!joined) {
            @Suppress("DEPRECATION")
            try { s.joinGroup(group) } catch (_: Exception) {}
        }
    }

    // ---- ağ izleme ----

    private fun watchLoop() {
        while (running) {
            try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { return }
            if (!running) return

            val now = System.currentTimeMillis()

            // Ekran kapalıyken WiFi güç tasarrufuna geçip multicast üyeliğini
            // sessizce düşürebiliyor. Adres değişmediği için arayüz taraması
            // bunu göremez; üyeliği düzenli olarak tazeliyoruz.
            if (now - lastRejoinAt >= REJOIN_MS) {
                lastRejoinAt = now
                socket?.let { rejoin(it) }
            }

            // Kendi yoklamamızı bile duymuyorsak soket ölmüştür: yeniden kur.
            if (now - lastRxAt >= DEAD_MS) {
                lastRxAt = now
                rebuilds++
                openSocket()
            }

            scanInterfaces(force = false)
        }
    }

    private fun rejoin(s: java.net.MulticastSocket) {
        val group = try { InetAddress.getByName(GROUP) } catch (_: Exception) { return }
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                try {
                    if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                    // Zaten üyeysek istisna atar ve yutulur; üyelik düşmüşse
                    // burada geri kazanılır.
                    s.joinGroup(InetSocketAddress(group, PORT), ni)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Arayüzleri tarar. Adresler değiştiyse (WiFi kopmuş, hotspot açılmış,
     * Wi-Fi Direct grubu kurulmuş) soketi yeniden kurar.
     */
    @Synchronized
    private fun scanInterfaces(force: Boolean) {
        val list = ArrayList<InetAddress>(4)
        try { list.add(InetAddress.getByName(GROUP)) } catch (_: Exception) {}

        val sig = StringBuilder()
        var ip = "-"
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address ?: continue
                    if (a !is java.net.Inet4Address) continue
                    sig.append(ni.name).append(':').append(a.hostAddress).append(';')
                    if (ip == "-") ip = a.hostAddress ?: "-"
                    ia.broadcast?.let { if (!list.contains(it)) list.add(it) }
                }
            }
        } catch (_: Exception) {
        }

        localIp = ip
        targets = list

        val now = sig.toString()
        val changed = now != signature
        signature = now

        if (force || changed || socket == null) {
            if (!force && changed) rebuilds++
            if (openSocket()) {
                // Yeni arayüzün yayın adresleri az önce hesaplandı; soket
                // yeniden kurulduğu için üyelikler de tazelendi.
                lastError = if (ip == "-") "ağ yok" else null
            }
        } else if (ip == "-") {
            lastError = "ağ yok"
        }
    }

    // ---- alım ----

    private fun receiveLoop() {
        val buf = ByteArray(Packet.MAX)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            val s = socket
            if (s == null) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            try {
                dp.length = buf.size
                s.receive(dp)
                lastRxAt = System.currentTimeMillis()
                onPacket(buf, dp.length)
            } catch (_: java.net.SocketTimeoutException) {
                // running bayrağını yeniden kontrol etmek için normal
            } catch (_: Exception) {
                // Soket yeniden kuruluyor olabilir: döngüden çıkmıyoruz,
                // yenisini bekliyoruz. Eskiden burada break vardı ve ağ
                // değişince alım kalıcı olarak duruyordu.
                if (!running) return
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
            }
        }
    }

    // ---- multicast kilidi ----

    private fun acquireLock() {
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("telsiz").apply {
                setReferenceCounted(false)
                acquire()
            }
            // Ekran kapalıyken WiFi radyosu güç tasarrufuna geçiyor ve
            // yayın/multicast paketleri düşüyor. Yüksek başarım kipi bunu
            // engelliyor. Yerini alan LOW_LATENCY kipi yalnızca uygulama
            // ön plandayken çalıştığı için burada işe yaramıyor.
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "telsiz-wifi")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {
        }
    }

    private fun releaseLock() {
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }
}
