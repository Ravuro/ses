package com.ravuro.telsiz

import android.content.Context
import kotlin.random.Random

class Prefs(ctx: Context) {

    companion object {
        /**
         * Hazır röle. Kullanıcı hiçbir şey yazmadan internet üzerinden de
         * konuşabilsin diye gömülü; alanı boşaltırsa yalnızca yerel ağ kalır,
         * başka bir adres yazarsa o kullanılır.
         */
        const val DEFAULT_RELAY = "https://alkayazilim.com/relay.php"
    }

    private val sp = ctx.applicationContext.getSharedPreferences("telsiz", Context.MODE_PRIVATE)

    var nick: String
        get() = sp.getString("nick", "") ?: ""
        set(v) = sp.edit().putString("nick", v).apply()

    var channel: Int
        get() = sp.getInt("channel", 1)
        set(v) = sp.edit().putInt("channel", v).apply()

    /**
     * Röle adresi uygulamaya gömülü: arayüzde ne görünüyor ne de
     * değiştirilebiliyor. İnternet üzerinden konuşma, kullanıcı hiçbir şey
     * yapmadan çalışan bir özellik olmalı.
     */
    val relayUrl: String get() = DEFAULT_RELAY

    /**
     * Kanalın davet kodu. Kod girildiyse kanal numarası da parola da ondan
     * türetiliyor; burada yalnızca paylaşmak ve ekranda göstermek için
     * saklanıyor. Kanal elle seçildiyse boş.
     */
    var invite: String
        get() = sp.getString("invite", "") ?: ""
        set(v) = sp.edit().putString("invite", v).apply()

    /** Kanal parolası. Boşsa ses şifresiz gider. */
    var passphrase: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()

    /**
     * Sıra numarasının bu açılışta başlayacağı yer.
     *
     * Şifrelemede nonce gönderen kimliği + sıra numarasından üretiliyor ve
     * GCM'de aynı nonce'un tekrarı anahtarı ifşa eder. Bu yüzden sayaç
     * uygulama yeniden başlayınca sıfırdan başlamıyor: her açılışta bir
     * öncekinin epey ilerisinden devam ediyor.
     */
    fun nextSeqBase(): Int {
        var base = sp.getInt("seqBase", 0)
        // Int sınırına yaklaşınca başa dön. Bu noktaya ancak on binlerce
        // açılıştan sonra gelinir.
        if (base < 0 || base > Int.MAX_VALUE - 1_000_000) base = 0
        sp.edit().putInt("seqBase", base + 100_000).apply()
        return base
    }

    /**
     * Kullanıcı telsizi açık bırakmak istiyor mu.
     *
     * Süreç öldürülmekle kullanıcının DURDUR'a basması dışarıdan aynı
     * görünüyor; ayırt edecek tek şey bu bayrak. Nabız buna bakıp
     * öldürülmüş telsizi geri getiriyor, kapatılmışı rahat bırakıyor.
     */
    var sessionWanted: Boolean
        get() = sp.getBoolean("wanted", false)
        set(v) = sp.edit().putBoolean("wanted", v).apply()

    /** Konuşmanın başında ve sonunda karşı tarafta çalan telsiz bipi. */
    var beep: Boolean
        get() = sp.getBoolean("beep", true)
        set(v) = sp.edit().putBoolean("beep", v).apply()

    /** Ses tuşuyla bas-konuş. */
    var volumePtt: Boolean
        get() = sp.getBoolean("volumePtt", true)
        set(v) = sp.edit().putBoolean("volumePtt", v).apply()

    /** Kurulum başına sabit kimlik: kendi paketlerimizi ve kopyaları elemek için. */
    val deviceId: Long
        get() {
            var id = sp.getLong("deviceId", 0L)
            if (id == 0L) {
                id = Random.nextLong()
                if (id == 0L) id = 1L
                sp.edit().putLong("deviceId", id).apply()
            }
            return id
        }
}
