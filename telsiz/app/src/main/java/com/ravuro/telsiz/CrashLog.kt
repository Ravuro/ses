package com.ravuro.telsiz

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Çökmeyi bir sonraki açılışa taşır.
 *
 * Uygulama sahada kullanılıyor: bilgisayar yok, `adb logcat` yok. Çökünce
 * geriye "kapandı" demekten başka bir şey kalmıyordu — sebebi ancak
 * telefonun kendi hata bildirimi ekranından, yığın izini parmakla
 * kaydırarak okunabiliyordu.
 *
 * Bu yüzden yığın izi diske yazılıyor ve bir sonraki açılışta tanı
 * kartında görünüyor; oradan tek dokunuşla panoya alınıp gönderilebiliyor.
 *
 * Sistemin kendi işleyicisi zincirin sonunda mutlaka çağrılıyor: yoksa
 * uygulama çökmek yerine donuyor.
 */
object CrashLog {

    /** Tanı kartını boğmayacak, sebebi göstermeye yetecek kadar. */
    private const val MAX_CHARS = 1400

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Prefs(app).lastCrash = describe(thread, error)
            } catch (_: Throwable) {
                // Çökme sırasında yazamıyorsak yapacak bir şey yok; asıl
                // olan sistemin işleyicisine ulaşmak.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = time + " · " + thread.name + "\n" + trace
        return if (text.length <= MAX_CHARS) text else text.substring(0, MAX_CHARS) + "…"
    }
}
