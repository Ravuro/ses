package com.ravuro.telsiz

/**
 * Aynı paket hem LAN'dan hem relay'den gelebilir; ayrıca UDP kopya üretebilir
 * ve sırayı bozabilir. 64 paketlik kayan pencere her sıra numarasını bir kez
 * kabul eder.
 *
 * Gönderen uygulamayı kapatıp açtığında sayacı sıfırdan başlar ama kimliği
 * (kurulum başına sabit) aynı kalır. Bu durumda gelen numaralar pencerenin
 * çok gerisinde kalır; ayırt edemezsek o kişiyi bir daha hiç duymayız.
 * Bu yüzden pencerenin dışından ısrarla gelen ya da uzun sessizlikten sonra
 * gelen paketler "yeniden başlamış" sayılıp pencere sıfırlanır.
 */
class SeqWindow {

    private companion object {
        const val WINDOW = 64
        const val RESTART_SILENCE_MS = 1500L
        const val RESTART_MISSES = 3
    }

    private var highest = -1
    private var mask = 0L
    private var lastAcceptAt = 0L
    private var misses = 0

    fun accept(seq: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (highest < 0) {
            reset(seq, now)
            return true
        }

        if (seq > highest) {
            val shift = seq - highest
            mask = if (shift >= WINDOW) 1L else (mask shl shift) or 1L
            highest = seq
            lastAcceptAt = now
            misses = 0
            return true
        }

        val back = highest - seq
        if (back < WINDOW) {
            val bit = 1L shl back
            if (mask and bit != 0L) return false      // kopya
            mask = mask or bit                        // sırasız ama yeni
            lastAcceptAt = now
            misses = 0
            return true
        }

        // Pencerenin çok gerisinde: ya fena halde gecikmiş bir paket ya da
        // gönderen yeniden başlamış. Sessizlik ya da üst üste gelen ıskalar
        // ikincisine işaret eder.
        misses++
        if (now - lastAcceptAt > RESTART_SILENCE_MS || misses >= RESTART_MISSES) {
            reset(seq, now)
            return true
        }
        return false
    }

    private fun reset(seq: Int, now: Long) {
        highest = seq
        mask = 1L
        lastAcceptAt = now
        misses = 0
    }
}
