package com.ravuro.telsiz

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Davet kodu: kanal numarası ve parolayı tek bir okunabilir dizede toplar.
 *
 * Şifreleme geldiğinden beri bir kanalı koruyan şey zaten parola; numarayı
 * bilmek tek başına işe yaramıyor, parolasız paketler doğrulamadan geçemiyor.
 * Ama o kurulum iki adımdı: "yedi numaralı kanala gel" ve "parola da şu".
 * İkincisi söylenmeyi unutunca insanlar parolasız kanalda buluşuyordu.
 *
 * Davet kodu bu iki adımı bire indiriyor. Kod 64 bitlik rastgele bir gizden
 * üretiliyor; kanal numarası da parola da o gizden çıkıyor. Yani numara artık
 * seçilen değil türetilen bir şey: kimse "kanal 7'yi deneyeyim" diyerek bir
 * yere giremiyor, çünkü o kanalın parolası kodun kendisi.
 *
 * Biçim Crockford Base32: I, L, O ve U yok — telefonda okurken 0/O ve 1/I
 * karışmasın diye. Okunurken yapılan tipik hatalar ([parse] içinde) sessizce
 * düzeltiliyor, iki baytlık sağlama da yanlış yazılan kodu kanala girmeden
 * yakalıyor.
 */
object Invite {

    /** Crockford Base32 alfabesi: I, L, O, U karışıklık yarattığı için yok. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val SECRET_BYTES = 8
    private const val SUM_BYTES = 2

    /** Ayraçsız kod uzunluğu: (8 + 2) bayt × 8 bit / 5 bit = 16 karakter. */
    const val LENGTH = 16

    /** Kod bu boydaki gruplara ayrılıp gösteriliyor: DK7M-3XQP-9WR2-VN4C. */
    private const val GROUP = 4

    /** Kanal numaraları 100..999 arasından türetiliyor. */
    private const val CHANNEL_MIN = 100
    private const val CHANNEL_SPAN = 900

    class Channel(
        /** Ayraçlı, gösterime hazır kod. */
        val code: String,
        val channel: Int,
        val passphrase: String
    )

    /** Yeni bir kanal: giz rastgele, kanal numarası ve parola ondan türüyor. */
    fun create(): Channel {
        val secret = ByteArray(SECRET_BYTES)
        SecureRandom().nextBytes(secret)
        return of(secret)
    }

    /**
     * Yazılan kodu çözer. Elle yazarken düşen ayraçlar, küçük harfler ve
     * O/0, I/1, L/1 karışması tolere ediliyor; sağlaması tutmayan kod
     * reddediliyor. Kod geçersizse null.
     */
    fun parse(text: String): Channel? {
        val norm = normalize(text)
        if (norm.length != LENGTH) return null

        val blob = ByteArray(SECRET_BYTES + SUM_BYTES)
        var acc = 0
        var bits = 0
        var out = 0
        for (ch in norm) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (out < blob.size) blob[out++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        if (out != blob.size) return null

        val secret = blob.copyOfRange(0, SECRET_BYTES)
        val sum = checksum(secret)
        for (i in 0 until SUM_BYTES) {
            if (blob[SECRET_BYTES + i] != sum[i]) return null
        }
        return of(secret)
    }

    /** Kodda yazım hatalarını düzeltip yalnızca alfabedeki harfleri bırakır. */
    fun normalize(text: String): String {
        val sb = StringBuilder(LENGTH)
        for (raw in text) {
            val c = when (val u = raw.uppercaseChar()) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> u
            }
            if (ALPHABET.indexOf(c) >= 0) sb.append(c)
        }
        return sb.toString()
    }

    /** Yazılırken ve gösterilirken kodu gruplara ayırır. */
    fun format(normalized: String): String {
        val sb = StringBuilder()
        for (i in normalized.indices) {
            if (i > 0 && i % GROUP == 0) sb.append('-')
            sb.append(normalized[i])
        }
        return sb.toString()
    }

    private fun of(secret: ByteArray): Channel {
        val norm = encode(secret + checksum(secret))
        return Channel(
            code = format(norm),
            channel = CHANNEL_MIN + (digest("telsiz-kanal", secret).let {
                ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
            } % CHANNEL_SPAN),
            // Parola kodun kendisi: 64 bit rastgelelik, elle uydurulan
            // parolalardan fazlası. Anahtar buradan PBKDF2 ile türetiliyor.
            passphrase = norm
        )
    }

    private fun encode(blob: ByteArray): String {
        val sb = StringBuilder(LENGTH)
        var acc = 0
        var bits = 0
        for (b in blob) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(acc ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(acc shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    private fun checksum(secret: ByteArray) =
        digest("telsiz-sağlama", secret).copyOfRange(0, SUM_BYTES)

    private fun digest(tag: String, secret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(tag.toByteArray(Charsets.UTF_8))
        md.update(secret)
        return md.digest()
    }
}
