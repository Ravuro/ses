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

    /** Ses tuşuyla bas-konuş (uygulama ekrandayken). */
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
