package com.ravuro.telsiz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Bas-konuşa basılı tutulunca açılan halka seçici: kanaldakiler düğmenin
 * üstünde bir yay üzerinde beliriyor, parmak birinin üzerine sürüklenince
 * ses yalnızca ona gidiyor. Kimse seçilmezse herkese.
 *
 * Ekranın tamamını kaplayan bir katman; parmak koordinatları bas-konuş
 * düğmesinden buraya aktarılıyor, çünkü basma düğmede başlıyor ve sürükleme
 * onun dışına çıkıyor.
 */
class TargetPicker(ctx: Context) : View(ctx) {

    private companion object {
        val SCRIM = 0xCC070A0F.toInt()
        val BUBBLE = 0xFF1B2230.toInt()
        val BUBBLE_LINE = 0xFF2A3542.toInt()
        val PICKED = 0xFF3EE08A.toInt()
        val PICKED_INK = 0xFF06170E.toInt()
        val TEXT = 0xFFE9EDF3.toInt()
        val MUTED = 0xFF79838F.toInt()
        const val MAX_PEERS = 8
    }

    class Item(val id: Long, val nick: String) {
        var x = 0f
        var y = 0f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var items: List<Item> = emptyList()
    private var cx = 0f
    private var cy = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var hasFinger = false

    /** Seçili kişi; 0 = herkes. */
    @Volatile var selectedId: Long = 0L
        private set

    var selectedNick: String? = null
        private set

    fun open(peers: List<Item>, centerX: Float, centerY: Float) {
        items = peers.take(MAX_PEERS)
        cx = centerX
        cy = centerY
        selectedId = 0L
        selectedNick = null
        hasFinger = false
        layoutItems()
        visibility = VISIBLE
        invalidate()
    }

    fun close() {
        visibility = GONE
        items = emptyList()
        selectedId = 0L
        selectedNick = null
    }

    fun moveFinger(x: Float, y: Float) {
        fingerX = x
        fingerY = y
        hasFinger = true

        var best: Item? = null
        var bestD = dp(46f)
        for (it in items) {
            val d = Math.hypot((x - it.x).toDouble(), (y - it.y).toDouble()).toFloat()
            if (d < bestD) {
                bestD = d
                best = it
            }
        }
        selectedId = best?.id ?: 0L
        selectedNick = best?.nick
        invalidate()
    }

    /**
     * Baloncukları düğmenin ÜSTÜNDE bir yaya diziyor.
     *
     * Tek geniş yay iki yüzden işe yaramıyor: az kişide uçlar neredeyse
     * yatay kalıyor (kişi düğmenin üstünde değil yanında duruyor), çok
     * kişide baloncuklar ekrandan taşıyor. Bu yüzden yayın genişliği kişi
     * sayısına göre ayarlanıyor ve beşten fazlası iki halkaya bölünüyor.
     */
    private fun layoutItems() {
        val n = items.size
        if (n == 0) return

        if (n <= 5) {
            place(items, dp(150f), spanFor(n))
        } else {
            // Dış halka belirgin biçimde daha uzakta, yoksa iki sıra
            // birbirine giriyor ve isimler çakışıyor. Genişliği ekrandan
            // taşmayacak kadar dar: yarıçap büyüdükçe aynı açı daha çok
            // yatay yer kaplıyor.
            val ic = 4
            place(items.subList(0, ic), dp(150f), spanFor(ic))
            place(items.subList(ic, n), dp(250f), spanFor(n - ic).coerceAtMost(72f))
        }

        // Ekrandan taşma: kenara yaklaşanı içeri çek.
        val m = dp(46f)
        for (it in items) {
            it.x = it.x.coerceIn(m, width - m)
            it.y = it.y.coerceIn(m, height - m)
        }
    }

    /** Kişi başına ~34°, ama toplamda 100°'yi geçmesin. */
    private fun spanFor(n: Int): Float =
        if (n <= 1) 0f else (n * 34f).coerceAtMost(100f)

    private fun place(list: List<Item>, radius: Float, span: Float) {
        val n = list.size
        for (i in list.indices) {
            // 270° tam yukarısı; yay onun etrafında simetrik açılıyor.
            val deg = if (n == 1) 270.0
            else 270.0 - span / 2.0 + span * i / (n - 1)
            val rad = Math.toRadians(deg)
            list[i].x = cx + (radius * Math.cos(rad)).toFloat()
            list[i].y = cy + (radius * Math.sin(rad)).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() && !hasFinger) return

        canvas.drawColor(SCRIM)

        // Seçiliye giden ip: parmağın nereye bağlandığını gösteriyor.
        val picked = items.firstOrNull { it.id == selectedId }
        if (picked != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = withAlpha(PICKED, 150)
            canvas.drawLine(cx, cy, picked.x, picked.y, paint)
        }

        val rBubble = dp(31f)
        for (it in items) {
            val on = it.id == selectedId
            val rr = if (on) rBubble * 1.12f else rBubble

            paint.style = Paint.Style.FILL
            paint.color = if (on) PICKED else BUBBLE
            canvas.drawCircle(it.x, it.y, rr, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.4f)
            paint.color = if (on) PICKED else BUBBLE_LINE
            canvas.drawCircle(it.x, it.y, rr, paint)

            paint.style = Paint.Style.FILL
            paint.typeface = Fonts.ui(context, bold = true)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(15f)
            paint.color = if (on) PICKED_INK else TEXT
            canvas.drawText(initials(it.nick), it.x, it.y + dp(5.5f), paint)

            paint.typeface = Fonts.ui(context)
            paint.textSize = dp(12f)
            paint.color = if (on) TEXT else MUTED
            val ad = if (it.nick.length > 8) it.nick.take(7) + "…" else it.nick
            canvas.drawText(ad, it.x, it.y + rr + dp(17f), paint)
        }

        // Ortadaki etiket: şu an kime gideceği
        paint.typeface = Fonts.mono(context, bold = true)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(12f)
        paint.letterSpacing = 0.16f
        paint.style = Paint.Style.FILL
        val label = selectedNick?.uppercase() ?: "HERKES"
        paint.color = if (selectedId == 0L) MUTED else PICKED
        canvas.drawText(label, cx, cy - dp(52f), paint)
        paint.letterSpacing = 0f

        if (items.isEmpty()) {
            paint.typeface = Fonts.ui(context)
            paint.textSize = dp(13f)
            paint.color = MUTED
            canvas.drawText("Kanalda başka kimse yok", cx, cy - dp(28f), paint)
        }
    }

    private fun initials(nick: String): String {
        val t = nick.trim()
        if (t.isEmpty()) return "??"
        val parts = t.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) (parts[0].take(1) + parts[1].take(1)).uppercase()
        else t.take(2).uppercase()
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
