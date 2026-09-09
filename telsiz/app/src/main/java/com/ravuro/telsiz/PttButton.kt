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
        val IDLE_A = 0xFF2C6BE8.toInt()
        val IDLE_B = 0xFF1B3F91.toInt()
        val TX_A = 0xFF3DDC84.toInt()
        val TX_B = 0xFF128A4E.toInt()
        val RX_A = 0xFF2AA9D6.toInt()
        val RX_B = 0xFF14566E.toInt()
        val RING = 0xFF232B39.toInt()
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
            val base = if (transmitting) TX_A else RX_A
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                val rr = r * (1f + p * 0.42f)
                paint.strokeWidth = dp(2.5f) * (1f - p)
                paint.color = withAlpha(base, ((1f - p) * 110).toInt())
                canvas.drawCircle(cx, cy, rr, paint)
            }
        }

        // Dış çerçeve
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = dp(1.5f)
        paint.color = RING
        canvas.drawCircle(cx, cy, r + dp(6), paint)

        // Gövde
        val (a, b) = when {
            !live -> 0xFF20262F.toInt() to 0xFF161B23.toInt()
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
        paint.color = withAlpha(Color.WHITE, if (live) 46 else 16)
        canvas.drawCircle(cx, cy, r - dp(3), paint)

        drawMic(canvas, cx, cy - r * 0.16f, r * 0.40f)

        // Etiket
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = withAlpha(Color.WHITE, if (live) 235 else 90)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(14f)
        paint.isFakeBoldText = true
        paint.letterSpacing = 0.12f
        val label = when {
            !live -> "KAPALI"
            transmitting -> "GÖNDERİYOR"
            else -> "BAS KONUŞ"
        }
        canvas.drawText(label, cx, cy + r * 0.62f, paint)
        paint.letterSpacing = 0f
        paint.isFakeBoldText = false

        if (animating) postInvalidateOnAnimation()
    }

    /** Mikrofon simgesi: kapsül + alt yay + sap. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val alpha = if (live) 255 else 110
        paint.color = withAlpha(Color.WHITE, alpha)

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
