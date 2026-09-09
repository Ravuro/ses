package com.ravuro.telsiz

/**
 * IMA ADPCM — 16 bit PCM'i 4 bit'e indirir (4:1).
 *
 * Her paket bağımsız kodlanır: kodlayıcı/çözücü durumu her çağrıda sıfırlanır.
 * Böylece UDP'de kaybolan bir paket kendinden sonrakileri bozmaz; kayıp yalnız
 * o 40 ms'lik parçada kalır.
 */
object Adpcm {

    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
        11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** [count] örneği kodlar, [out]'a yazılan bayt sayısını döner. */
    fun encode(pcm: ShortArray, count: Int, out: ByteArray): Int {
        var predictor = 0
        var index = 0
        var outPos = 0
        var pending = 0
        var half = false

        for (i in 0 until count) {
            val step = STEP[index]
            var diff = pcm[i].toInt() - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var t = step
            if (diff >= t) { code = code or 4; diff -= t }
            t = t shr 1
            if (diff >= t) { code = code or 2; diff -= t }
            t = t shr 1
            if (diff >= t) { code = code or 1 }

            var dq = step shr 3
            if (code and 4 != 0) dq += step
            if (code and 2 != 0) dq += step shr 1
            if (code and 1 != 0) dq += step shr 2

            predictor = if (code and 8 != 0) predictor - dq else predictor + dq
            if (predictor > 32767) predictor = 32767
            if (predictor < -32768) predictor = -32768
            index += INDEX[code]
            if (index < 0) index = 0
            if (index > 88) index = 88

            if (!half) {
                pending = code
                half = true
            } else {
                out[outPos++] = (pending or (code shl 4)).toByte()
                half = false
            }
        }
        if (half) out[outPos++] = pending.toByte()
        return outPos
    }

    /** [len] baytı çözer, [out]'a yazılan örnek sayısını döner. */
    fun decode(data: ByteArray, off: Int, len: Int, out: ShortArray): Int {
        var predictor = 0
        var index = 0
        var n = 0

        for (i in 0 until len) {
            val b = data[off + i].toInt()
            for (nib in 0 until 2) {
                if (n >= out.size) return n
                val code = if (nib == 0) (b and 0x0F) else ((b shr 4) and 0x0F)
                val step = STEP[index]

                var dq = step shr 3
                if (code and 4 != 0) dq += step
                if (code and 2 != 0) dq += step shr 1
                if (code and 1 != 0) dq += step shr 2

                predictor = if (code and 8 != 0) predictor - dq else predictor + dq
                if (predictor > 32767) predictor = 32767
                if (predictor < -32768) predictor = -32768
                index += INDEX[code]
                if (index < 0) index = 0
                if (index > 88) index = 88

                out[n++] = predictor.toShort()
            }
        }
        return n
    }
}
