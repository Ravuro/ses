package com.ravuro.telsiz

import android.content.Context
import kotlin.random.Random

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("telsiz", Context.MODE_PRIVATE)

    var nick: String
        get() = sp.getString("nick", "") ?: ""
        set(v) = sp.edit().putString("nick", v).apply()

    var channel: Int
        get() = sp.getInt("channel", 1)
        set(v) = sp.edit().putInt("channel", v).apply()

    var relayUrl: String
        get() = sp.getString("relay", "") ?: ""
        set(v) = sp.edit().putString("relay", v).apply()

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
