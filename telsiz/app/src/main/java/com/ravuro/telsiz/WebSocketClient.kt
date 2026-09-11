package com.ravuro.telsiz

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Küçük bir WebSocket istemcisi (RFC 6455).
 *
 * Hazır bir kütüphane yerine elde yazıldı: uygulama Android SDK'sı olmadan
 * derlendiği için bağımlılıkları en aza indirmek gerekiyordu. Telsizin
 * ihtiyacı olan kadarını yapar — ikili çerçeve gönder/al, ping'e pong ile
 * cevap ver. Parçalanmış (fragmented) çerçeve beklenmiyor: röle her ses
 * paketini tek çerçevede yolluyor.
 */
class WebSocketClient(private val url: String, private val listener: Listener) {

    interface Listener {
        fun onBinary(data: ByteArray)
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        const val MAX_FRAME = 65536
    }

    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val sendLock = Object()
    private val rnd = SecureRandom()

    @Volatile var open = false
        private set

    /** El sıkışmayı tamamlar; başarısız olursa istisna atar. */
    fun connect() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: throw IOException("adreste sunucu adı yok")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80

        var path = uri.rawPath ?: ""
        if (path.isEmpty()) path = "/"
        if (uri.rawQuery != null) path += "?" + uri.rawQuery

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 10000)
        raw.tcpNoDelay = true
        // Sunucu 25 sn'de bir ping atıyor; bundan uzun sessizlik kopma sayılır.
        raw.soTimeout = 45000

        val s: Socket = if (secure) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as SSLSocket
            // Sertifika zincirini doğrulamak yetmiyor: SSLSocket varsayılan
            // olarak sertifikadaki adın bağlandığımız sunucuya ait olup
            // olmadığına BAKMIYOR. Bu açık bırakılırsa herhangi bir geçerli
            // sertifika sahibi araya girip sesi dinleyebilir. HttpURLConnection
            // bunu kendiliğinden yapıyor, çıplak soket yapmıyor.
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
            ssl
        } else {
            raw
        }

        val out = s.getOutputStream()
        val inp = BufferedInputStream(s.getInputStream())

        val keyBytes = ByteArray(16)
        rnd.nextBytes(keyBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val req = StringBuilder()
        req.append("GET ").append(path).append(" HTTP/1.1\r\n")
        req.append("Host: ").append(host)
        if ((secure && port != 443) || (!secure && port != 80)) req.append(':').append(port)
        req.append("\r\n")
        req.append("Upgrade: websocket\r\n")
        req.append("Connection: Upgrade\r\n")
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        req.append("Sec-WebSocket-Version: 13\r\n\r\n")
        out.write(req.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        val status = readLine(inp) ?: throw IOException("sunucu cevap vermedi")
        if (!status.contains(" 101")) throw IOException("sunucu yükseltmeyi kabul etmedi: $status")

        var accept: String? = null
        while (true) {
            val line = readLine(inp) ?: throw IOException("başlıklar bitmeden koptu")
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0 && line.substring(0, c).trim().equals("Sec-WebSocket-Accept", true)) {
                accept = line.substring(c + 1).trim()
            }
        }
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + GUID).toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        if (accept != expected) throw IOException("el sıkışma doğrulanamadı")

        socket = s
        input = inp
        output = out
        open = true
    }

    /** Bağlantı kopana kadar çerçeve okur. Çağıran thread'i bloklar. */
    fun readLoop() {
        val inp = input ?: return
        try {
            while (open) {
                val b0 = inp.read()
                if (b0 < 0) break
                val b1 = inp.read()
                if (b1 < 0) break

                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = ((next(inp) shl 8) or next(inp)).toLong()
                } else if (len == 127L) {
                    len = 0
                    for (i in 0 until 8) len = (len shl 8) or next(inp).toLong()
                }
                if (len < 0 || len > MAX_FRAME) throw IOException("çerçeve fazla büyük: $len")

                val mask = if (masked) ByteArray(4) { next(inp).toByte() } else null
                val payload = ByteArray(len.toInt())
                readFully(inp, payload)
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    OP_BINARY -> listener.onBinary(payload)
                    OP_PING -> sendFrame(OP_PONG, payload, payload.size)
                    OP_CLOSE -> break
                    else -> {} // metin/pong/devam: telsizde kullanılmıyor
                }
            }
        } finally {
            open = false
        }
    }

    fun sendBinary(data: ByteArray, len: Int) {
        if (!open) return
        sendFrame(OP_BINARY, data, len)
    }

    fun close() {
        open = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    // ---- çerçeve yazımı ----

    private fun sendFrame(opcode: Int, data: ByteArray, len: Int) {
        val out = output ?: return
        synchronized(sendLock) {
            val h = ByteArrayOutputStream(14)
            h.write(0x80 or opcode)          // FIN + opcode
            when {
                len < 126 -> h.write(0x80 or len)
                len <= 0xFFFF -> {
                    h.write(0x80 or 126)
                    h.write((len shr 8) and 0xFF)
                    h.write(len and 0xFF)
                }
                else -> {
                    h.write(0x80 or 127)
                    for (i in 7 downTo 0) h.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
            // İstemciden sunucuya giden her çerçeve maskelenmek zorunda.
            val mask = ByteArray(4)
            rnd.nextBytes(mask)
            h.write(mask)

            val body = ByteArray(len)
            for (i in 0 until len) body[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()

            try {
                out.write(h.toByteArray())
                out.write(body)
                out.flush()
            } catch (e: IOException) {
                open = false
                throw e
            }
        }
    }

    // ---- okuma yardımcıları ----

    private fun next(inp: InputStream): Int {
        val b = inp.read()
        if (b < 0) throw IOException("bağlantı koptu")
        return b
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("bağlantı koptu")
            off += n
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("başlık fazla uzun")
        }
    }
}
