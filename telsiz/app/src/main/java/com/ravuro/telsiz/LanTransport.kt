package com.ravuro.telsiz

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Şebeke gerekmeyen yol: aynı WiFi ağındaki ya da bir telefonun hotspot'una
 * bağlı cihazlar birbirini doğrudan duyar.
 *
 * Hem multicast grubuna hem de her arayüzün yayın (broadcast) adresine
 * gönderiyoruz: bazı router'lar ve çoğu hotspot multicast'i düşürürken
 * subnet broadcast'i geçiriyor, bazılarında tersi oluyor. İkisini birden
 * göndermek "kurdum, çalışmadı" durumunu büyük ölçüde ortadan kaldırıyor;
 * kopyalar zaten sıra numarasıyla eleniyor.
 */
class LanTransport(
    private val ctx: Context,
    private val onPacket: (ByteArray, Int) -> Unit
) {
    companion object {
        const val PORT = 47771
        const val GROUP = "239.255.42.99"
    }

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var rxThread: Thread? = null
    private var refreshThread: Thread? = null

    @Volatile private var targets: List<InetAddress> = emptyList()
    @Volatile var localIp: String = "-"
        private set
    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (running) return
        running = true

        // Multicast/broadcast paketleri WiFi sürücüsü tarafından süzülmesin.
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("telsiz").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        try {
            // MulticastSocket kurucusu SO_REUSEADDR'ı zaten açıyor.
            val s = MulticastSocket(PORT)
            s.broadcast = true
            s.timeToLive = 1
            s.soTimeout = 1000
            joinOnAllInterfaces(s)
            socket = s
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: "soket açılamadı"
            running = false
            releaseLock()
            return
        }

        refreshTargets()

        rxThread = Thread({ receiveLoop() }, "telsiz-lan-rx").apply { isDaemon = true; start() }
        refreshThread = Thread({
            while (running) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                refreshTargets()
            }
        }, "telsiz-lan-net").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        rxThread?.interrupt()
        refreshThread?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        rxThread = null
        refreshThread = null
        releaseLock()
    }

    fun send(data: ByteArray, len: Int) {
        val s = socket ?: return
        for (addr in targets) {
            try {
                s.send(DatagramPacket(data, 0, len, addr, PORT))
            } catch (_: Exception) {
                // Tek bir arayüz kapanmış olabilir; diğerlerine göndermeye devam.
            }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(Packet.MAX)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                dp.length = buf.size
                socket?.receive(dp) ?: break
                onPacket(buf, dp.length)
            } catch (_: java.net.SocketTimeoutException) {
                // soTimeout: running bayrağını tekrar kontrol etmek için normal
            } catch (e: Exception) {
                if (running) lastError = e.message
                break
            }
        }
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
                    s.joinGroup(java.net.InetSocketAddress(group, PORT), ni)
                    joined = true
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        if (!joined) {
            @Suppress("DEPRECATION")
            try { s.joinGroup(group) } catch (_: Exception) {}
        }
    }

    private fun refreshTargets() {
        val list = ArrayList<InetAddress>(4)
        try { list.add(InetAddress.getByName(GROUP)) } catch (_: Exception) {}

        var ip = "-"
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address ?: continue
                    if (a is java.net.Inet4Address) {
                        if (ip == "-") ip = a.hostAddress ?: "-"
                        ia.broadcast?.let { if (!list.contains(it)) list.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        localIp = ip
        targets = list
    }

    private fun releaseLock() {
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
