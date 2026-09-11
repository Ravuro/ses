package com.ravuro.telsiz

/**
 * Tel formatı — LAN (UDP) ve relay (WebSocket) aynı baytları taşır,
 * böylece iki yol arasında dönüşüm gerekmez ve tekilleştirme tek yerde yapılır.
 *
 *  0..3   sihir "TLSZ"
 *  4      sürüm
 *  5      tip (1 ses, 2 yoklama)
 *  6..7   kanal (uint16, big-endian)
 *  8..15  gönderen kimliği (int64)
 *  16..19 sıra numarası (int32)
 *  20..21 yük uzunluğu (uint16)
 *  22..23 ADPCM başlangıç öngörüsü (int16)
 *  24     ADPCM başlangıç adımı (0-88)
 *  25..32 hedef (int64; 0 = kanaldaki herkes)
 *  33     bayraklar (bit 0: yük şifreli)
 *  34..   yük
 *
 * Ses paketleri kendi ADPCM başlangıç durumunu taşıyor: kodlayıcı kareler
 * boyunca akmaya devam ediyor ama her paket tek başına çözülebiliyor.
 *
 * Başlık şifrelenmiyor — kanal ve hedef yönlendirme için gerekli — ama
 * kimliği doğrulanıyor: şifreli paketlerde başlığın tamamı GCM'in ek
 * doğrulama verisi olarak kullanılıyor, dolayısıyla tek bir baytı bile
 * değiştirilen paket çözülemiyor.
 *
 * Hedef alanı tek başına gizlilik sağlamaz (paket yine herkese ulaşır,
 * alıcı kendine değilse çalmaz). Gizlilik parolalı kanaldan geliyor.
 */
object Packet {

    const val HEADER = 34
    const val MAX = 1400

    // 2: ADPCM durumu başlığa eklendi.
    // 3: hedef alanı eklendi (kişiye özel ses).
    // 4: bayrak baytı eklendi (şifreli yük).
    // Eski sürüm paketleri reddediyor — herkesin güncellemesi gerekiyor,
    // karışık ses duyulmasındansa iyi.
    const val VERSION: Byte = 4

    /** Yük AES-GCM ile şifreli. */
    const val FLAG_ENCRYPTED = 1
    const val TYPE_AUDIO: Byte = 1
    const val TYPE_PRESENCE: Byte = 2
    /** Yükü tek bayt: 0 başlangıç bipi, 1 bitiş bipi. Ton alıcıda üretilir. */
    const val TYPE_BEEP: Byte = 3

    class Parsed {
        var type: Byte = 0
        var channel: Int = 0
        var senderId: Long = 0
        var seq: Int = 0
        var payloadOff: Int = 0
        var payloadLen: Int = 0
        var predictor: Int = 0
        var index: Int = 0
        /** 0 ise kanaldaki herkese. */
        var target: Long = 0
        var flags: Int = 0

        val encrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
    }

    /**
     * Yalnızca başlığı yazar; yükü çağıran yerleştirir. Şifreli gönderimde
     * başlık, şifrelemenin ek doğrulama verisi olduğu için önce hazır olmalı.
     */
    fun buildHeader(
        out: ByteArray,
        type: Byte,
        channel: Int,
        senderId: Long,
        seq: Int,
        payloadLen: Int,
        predictor: Int = 0,
        index: Int = 0,
        target: Long = 0,
        flags: Int = 0
    ): Int {
        out[0] = 'T'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 'S'.code.toByte()
        out[3] = 'Z'.code.toByte()
        out[4] = VERSION
        out[5] = type
        putShort(out, 6, channel)
        putLong(out, 8, senderId)
        putInt(out, 16, seq)
        putShort(out, 20, payloadLen)
        putShort(out, 22, predictor and 0xFFFF)
        out[24] = index.toByte()
        putLong(out, 25, target)
        out[33] = flags.toByte()
        return HEADER
    }

    /** Şifresiz paket: başlık + yükün kopyası. */
    fun build(
        out: ByteArray,
        type: Byte,
        channel: Int,
        senderId: Long,
        seq: Int,
        payload: ByteArray,
        payloadLen: Int,
        predictor: Int = 0,
        index: Int = 0,
        target: Long = 0
    ): Int {
        buildHeader(out, type, channel, senderId, seq, payloadLen, predictor, index, target, 0)
        System.arraycopy(payload, 0, out, HEADER, payloadLen)
        return HEADER + payloadLen
    }

    fun parse(buf: ByteArray, len: Int, into: Parsed): Boolean {
        // Üst sınır: yerel ağ paketi zaten [MAX] tamponuna sığıyor ama röle
        // kaydı 64 KB'a kadar uzunluk bildirebiliyor. Sınırsız bırakılırsa
        // tek bir bozuk kayıt yüz kilobaytlık dizi ayırtıyor.
        if (len < HEADER || len > MAX) return false
        if (buf[0] != 'T'.code.toByte() || buf[1] != 'L'.code.toByte() ||
            buf[2] != 'S'.code.toByte() || buf[3] != 'Z'.code.toByte()
        ) return false
        if (buf[4] != VERSION) return false

        into.type = buf[5]
        into.channel = getShort(buf, 6)
        into.senderId = getLong(buf, 8)
        into.seq = getInt(buf, 16)
        into.payloadLen = getShort(buf, 20)
        // int16 olarak yorumla: öngörü negatif olabiliyor
        into.predictor = getShort(buf, 22).toShort().toInt()
        into.index = buf[24].toInt()
        into.target = getLong(buf, 25)
        into.flags = buf[33].toInt() and 0xFF
        into.payloadOff = HEADER
        if (into.payloadLen < 0 || HEADER + into.payloadLen > len) return false
        return true
    }

    /**
     * Paket bizim biçimimizde ama sürümü başka mı?
     *
     * Sürümü tutmayan paket sessizce düşüyor ve kullanıcı "kimseyi
     * duymuyorum" diyor. Hangi sürümden geldiğini bilirsek bunu
     * söyleyebiliyoruz. Bizim sürümümüzse ya da bizim paketimiz değilse -1.
     */
    fun foreignVersion(buf: ByteArray, len: Int): Int {
        if (len < HEADER) return -1
        if (buf[0] != 'T'.code.toByte() || buf[1] != 'L'.code.toByte() ||
            buf[2] != 'S'.code.toByte() || buf[3] != 'Z'.code.toByte()
        ) return -1
        val v = buf[4].toInt() and 0xFF
        return if (v == VERSION.toInt()) -1 else v
    }

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun putLong(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (56 - i * 8)) and 0xFF).toByte()
    }

    private fun getShort(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun getInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun getLong(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
