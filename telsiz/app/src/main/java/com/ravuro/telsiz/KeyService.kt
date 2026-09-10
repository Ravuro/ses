package com.ravuro.telsiz

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Ses tuşunu sistem seviyesinde yakalar.
 *
 * Neden gerekiyor: normalde ekran kapalıyken tuşları alabilmek için bir medya
 * oturumu açıp sesi "uzak" olarak bildiriyoruz ([KeyPtt]). Bu yol Android'in
 * ses tuşunu "şu an ses çalan" uygulamaya yönlendirmesine dayanıyor ve bazı
 * üretici arayüzlerinde (Xiaomi/MIUI gibi) hiç çalışmıyor.
 *
 * Erişilebilirlik servisi tuş olaylarını sistemin kendisinden alıyor, yani
 * medya oturumu önceliğine bağlı değil. Karşılığında kullanıcının bu servisi
 * Ayarlar'dan elle açması gerekiyor — uygulama bu izni açılır pencereyle
 * isteyemez, yalnızca durumu okuyup ilgili ayar ekranına götürebilir.
 *
 * Buradan gelen olaylar bas/bırak olarak tam geliyor, dolayısıyla medya
 * oturumundaki gibi bırakma kuyruğu yok.
 */
class KeyService : AccessibilityService() {

    companion object {
        /** Ayarlarda etkin servis listesinde aranacak ad. */
        fun componentName(pkg: String): String = "$pkg/${KeyService::class.java.name}"
    }

    @Volatile private var holding = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (!TelsizService.isRunning) return false
        if (!Prefs(this).volumePtt) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !holding) {
                    holding = true
                    TelsizService.keyDown()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (holding) {
                    holding = false
                    TelsizService.keyUp()
                }
            }
        }
        // Olayı tüketiyoruz: telsiz açıkken ses yükseltme tuşu bas-konuş
        // demek, ses ayarı değil. Kapalıyken yukarıda false dönüyoruz.
        return true
    }

    override fun onInterrupt() {
        if (holding) {
            holding = false
            TelsizService.keyUp()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tuş olayları dışında bir şeye ihtiyacımız yok.
    }
}
