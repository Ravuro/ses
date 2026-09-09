package com.ravuro.telsiz

import android.content.Context
import android.graphics.Typeface

/**
 * Uygulamanın yazı tipleri. res/ olmadığı için APK'nın assets/ klasöründen
 * yükleniyor — assets derlenmiyor, olduğu gibi paketleniyor.
 *
 * Space Grotesk arayüz için, JetBrains Mono ölçüm değerleri için: kanal
 * numarası, IP, gecikme gibi şeyler eşit genişlikte olunca gözle taranması
 * kolaylaşıyor ve değer değişince satır oynamıyor.
 *
 * Her ikisi de SIL Open Font License (assets/fonts/OFL-*.txt); Latin ve
 * Türkçe karakterlere indirgenmiş hâlleri paketleniyor.
 */
object Fonts {

    private val cache = HashMap<String, Typeface>()

    private fun load(ctx: Context, file: String, fallback: Typeface): Typeface {
        cache[file]?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/$file")
        } catch (_: Exception) {
            fallback
        }
        cache[file] = t
        return t
    }

    fun ui(ctx: Context, bold: Boolean = false): Typeface =
        if (bold) load(ctx, "SpaceGrotesk-Bold.ttf", Typeface.DEFAULT_BOLD)
        else load(ctx, "SpaceGrotesk-Regular.ttf", Typeface.DEFAULT)

    fun mono(ctx: Context, bold: Boolean = false): Typeface =
        if (bold) load(ctx, "JetBrainsMono-Bold.ttf", Typeface.MONOSPACE)
        else load(ctx, "JetBrainsMono-Regular.ttf", Typeface.MONOSPACE)
}
