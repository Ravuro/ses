package com.ravuro.telsiz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Bas-konuş düğmesi.
 *
 * Kaynak dosyası olmadığı için tamamen çiziliyor. Konuşurken dışarı doğru
 * açılan halkalar, karşı taraf konuşurken yeşil nabız: durumu ekrana bakmadan
 * da anlaşılsın diye — telsizde göz genellikle ekranda olmuyor.
 */
class PttButton(ctx: Context) : View(ctx) {

    private companion object {
        // Boşta çelik, sen konuşurken yeşil, karşı taraf konuşurken camgöbeği.
        // Renk kodu uygulamanın her yerinde aynı: yeşil = giden, camgöbeği = gelen.
        val IDLE_A = 0xFF1E2733.toInt()
        val IDLE_B = 0xFF141A23.toInt()
        val TX_A = 0xFF55EC9C.toInt()
        val TX_B = 0xFF12A664.toInt()
        val RX_A = 0xFF22303D.toInt()
        val RX_B = 0xFF141A23.toInt()
        val OFF_A = 0xFF171E27.toInt()
        val OFF_B = 0xFF101419.toInt()

        val GLYPH_IDLE = 0xFF8C97A4.toInt()
        val GLYPH_TX = 0xFF06170E.toInt()
        val GLYPH_RX = 0xFF9AA5B2.toInt()
        val GLYPH_OFF = 0xFF4C5764.toInt()

        val RING_IDLE = 0xFF1B222C.toInt()
        val RING_IDLE_IN = 0xFF232C38.toInt()
        val RING_TX = 0xFF1E4633.toInt()
        val RING_TX_IN = 0xFF2C6B4B.toInt()
        val RING_RX_IN = 0xFF22D3EE.toInt()

        val PULSE_TX = 0xFF3EE08A.toInt()
        val PULSE_RX = 0xFF22D3EE.toInt()
    }

    var transmitting = false
        set(v) { if (field != v) { field = v; tick() } }

    var receiving = false
        set(v) { if (field != v) { field = v; tick() } }

    var live = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Path()
    private val rect = RectF()
    private var phase = 0f
    private var lastFrame = 0L

    private fun tick() {
        lastFrame = System.currentTimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(14)

        val animating = transmitting || receiving
        if (animating) {
            val now = System.currentTimeMillis()
            val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceAtMost(64L)
            lastFrame = now
            phase = (phase + dt / 1400f) % 1f
        } else {
            phase = 0f
            lastFrame = 0L
        }

        // Dışarı açılan halkalar
        if (animating) {
            paint.style = Paint.Style.STROKE
            paint.shader = null
            val base = if (transmitting) PULSE_TX else PULSE_RX
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                val rr = r * (1f + p * 0.42f)
                paint.strokeWidth = dp(2.5f) * (1f - p)
                paint.color = withAlpha(base, ((1f - p) * 110).toInt())
                canvas.drawCircle(cx, cy, rr, paint)
            }
        }

        // İki hâlkalı çerçeve: dıştaki nötr, içteki duruma göre renkleniyor —
        // gelen ses camgöbeği bir çizgiyle belli oluyor.
        val ringOut = if (transmitting) RING_TX else RING_IDLE
        val ringIn = when {
            !live -> RING_IDLE
            transmitting -> RING_TX_IN
            receiving -> RING_RX_IN
            else -> RING_IDLE_IN
        }
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = dp(1f)
        paint.color = ringOut
        canvas.drawCircle(cx, cy, r + dp(13), paint)
        paint.color = ringIn
        canvas.drawCircle(cx, cy, r + dp(5), paint)

        // Gövde
        val (a, b) = when {
            !live -> OFF_A to OFF_B
            transmitting -> TX_A to TX_B
            receiving -> RX_A to RX_B
            else -> IDLE_A to IDLE_B
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy - r * 0.35f, r * 1.5f,
            intArrayOf(a, b), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // İç parlama halkası
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = withAlpha(Color.WHITE, if (live) 40 else 14)
        canvas.drawCircle(cx, cy, r - dp(3), paint)

        val glyphColor = when {
            !live -> GLYPH_OFF
            transmitting -> GLYPH_TX
            receiving -> GLYPH_RX
            else -> GLYPH_IDLE
        }
        drawMic(canvas, cx, cy - r * 0.16f, r * 0.40f, glyphColor)

        // Etiket
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = glyphColor
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Fonts.mono(context, bold = true)
        paint.textSize = dp(12f)
        paint.letterSpacing = 0.16f
        val label = when {
            !live -> "KAPALI"
            transmitting -> "GÖNDERİYOR"
            else -> "BAS KONUŞ"
        }
        canvas.drawText(label, cx, cy + r * 0.62f, paint)
        paint.letterSpacing = 0f

        if (animating) postInvalidateOnAnimation()
    }

    /** Mikrofon simgesi: kapsül + alt yay + sap. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        paint.color = color

        paint.style = Paint.Style.FILL
        val w = s * 0.46f
        rect.set(cx - w / 2, cy - s * 0.72f, cx + w / 2, cy + s * 0.18f)
        canvas.drawRoundRect(rect, w / 2, w / 2, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.13f
        paint.strokeCap = Paint.Cap.ROUND
        glyph.reset()
        rect.set(cx - s * 0.52f, cy - s * 0.42f, cx + s * 0.52f, cy + s * 0.52f)
        glyph.addArc(rect, 20f, 140f)
        canvas.drawPath(glyph, paint)

        canvas.drawLine(cx, cy + s * 0.52f, cx, cy + s * 0.86f, paint)
    }

    private fun withAlpha(color: Int, a: Int) =
        (color and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dp(v: Int) = v * resources.displayMetrics.density
}
