package com.ravuro.telsiz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Nabız. Telsiz açıkken dakikada bir çalıyor ve servise "hâlâ ayakta mısın"
 * diye soruyor.
 *
 * Neden gerekiyor: uygulama arka planda kendiliğinden susuyordu. Sebep tek
 * değil, üç ayrı şey aynı belirtiyi veriyor:
 *
 *  - **Doze.** Telefon kımıldamadan bir süre durunca sistem uykuya geçiyor.
 *    Ön plan servisi ve uyanıklık kilidi işlemciyi ayakta tutuyor ama ağ
 *    erişimi yine de kısılabiliyor; uzun bekleyen sorgu sessizce ölüyor.
 *  - **Ölmüş soket.** Mobil veride telsiz uyuyunca açık soket kapanmıyor,
 *    donuyor. Okuma zaman aşımına kadar asılı kalıyor, bayrak "bağlı"
 *    diyor, ses gelmiyor. En sinsi hâli bu: her şey çalışıyor görünüyor.
 *  - **Süreç öldürülmesi.** Xiaomi/Oppo arayüzleri uygulamayı topluca
 *    kapatıyor. START_STICKY bunu bellek darlığında telafi ediyor ama
 *    üretici temizliğinde etmiyor.
 *
 * Alarm üçünü de karşılıyor: uykuda bile çalıyor (`AllowWhileIdle`), servis
 * ayaktaysa bileşenleri denetletiyor, süreç ölmüşse yeniden başlatıyor.
 * Alıcı manifestte tanımlı, yani süreç öldükten sonra da alarmı alabiliyor.
 *
 * Doze'da sistem bu alarmları uygulama başına ~9 dakikada bire kısıyor; o
 * yüzden dakikada bir istiyoruz ama en kötü ihtimalle dokuz dakikada bir
 * toparlanma oluyor. Hiç toparlanmamaktan iyi.
 */
class Watchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        try {
            TelsizService.poke(context)
        } catch (_: Exception) {
        }
        arm(context)
    }

    companion object {
        const val ACTION = "com.ravuro.telsiz.NABIZ"
        private const val PERIOD_MS = 60_000L
        private const val REQUEST = 41

        private fun intent(ctx: Context): PendingIntent {
            val i = Intent(ACTION).setClass(ctx, Watchdog::class.java)
            return PendingIntent.getBroadcast(
                ctx, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun arm(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + PERIOD_MS
            try {
                // Kesin alarm izni olmayan sürümlerde tam zamanlı isteme
                // hakkımız olmayabiliyor; o zaman yaklaşık olanı da iş görür.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, at, intent(ctx))
                }
            } catch (_: Exception) {
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
                } catch (_: Exception) {
                }
            }
        }

        fun disarm(ctx: Context) {
            try {
                ctx.getSystemService(AlarmManager::class.java)?.cancel(intent(ctx))
            } catch (_: Exception) {
            }
        }
    }
}
