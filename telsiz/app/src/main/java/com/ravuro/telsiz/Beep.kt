package com.ravuro.telsiz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Telsiz bipleri.
 *
 * Ton karşı tarafta üretiliyor, ses kanalından geçirilmiyor: ADPCM'den
 * geçen bir bip cızırtılı çıkardı ve ilk/son paket kaybolursa hiç
 * duyulmazdı. Ağda giden şey yalnızca "bip çal" işareti.
 *
 * Başlangıç bipi tek kısa ton — "konuşuyorum" der. Bitiş bipi inen iki ton:
 * gerçek telsizlerdeki "tamam" işareti, sözün bittiğini belli eder.
 */
object Beep {

    const val START: Byte = 0
    const val END: Byte = 1

    private const val SR = AudioEngine.SAMPLE_RATE
    private const val AMP = 6800
    /** Kenar yumuşatma: tonu birden kesmek duyulur bir çıtırtı yapıyor. */
    private const val FADE_MS = 6

    private val startPcm: ShortArray by lazy {
        build(listOf(Tone(1150.0, 70)))
    }

    private val endPcm: ShortArray by lazy {
        build(listOf(Tone(1500.0, 55), Tone(1050.0, 85)))
    }

    fun pcm(kind: Byte): ShortArray = if (kind == END) endPcm else startPcm

    private class Tone(val freq: Double, val ms: Int)

    private fun build(tones: List<Tone>): ShortArray {
        val total = tones.sumOf { it.ms * SR / 1000 }
        val out = ShortArray(total)
        var at = 0
        for (t in tones) {
            val n = t.ms * SR / 1000
            val fade = minOf(FADE_MS * SR / 1000, n / 2)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                    i >= n - fade -> 0.5 - 0.5 * cos(PI * (n - 1 - i) / fade)
                    else -> 1.0
                }
                val v = AMP * env * sin(2 * PI * t.freq * i / SR)
                out[at + i] = v.toInt().coerceIn(-32768, 32767).toShort()
            }
            at += n
        }
        return out
    }
}
