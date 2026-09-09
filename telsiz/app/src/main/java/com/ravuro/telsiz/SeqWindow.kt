package com.ravuro.telsiz

/**
 * Aynı paket hem LAN'dan hem relay'den gelebilir; ayrıca UDP kopya/sıra
 * bozukluğu yapabilir. 64 paketlik kayan pencere ile her sıra numarasını
 * bir kez kabul ederiz.
 */
class SeqWindow {
    private var highest = -1
    private var mask = 0L

    fun accept(seq: Int): Boolean {
        if (highest < 0) {
            highest = seq
            mask = 1L
            return true
        }
        if (seq > highest) {
            val shift = seq - highest
            mask = if (shift >= 64) 1L else (mask shl shift) or 1L
            highest = seq
            return true
        }
        val back = highest - seq
        if (back >= 64) {
            // Gönderen uygulamayı yeniden başlatmış olabilir: sayaç sıfırlanmıştır.
            if (back > 1000) {
                highest = seq
                mask = 1L
                return true
            }
            return false
        }
        val bit = 1L shl back
        if (mask and bit != 0L) return false
        mask = mask or bit
        return true
    }
}
