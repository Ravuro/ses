package com.ravuro.telsiz

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Ekran kapalıyken ses tuşuyla konuşabilmek için.
 *
 * Ekran kapandığında tuş olayları uygulamaya gelmez; onları sisteme
 * yönlendirtmenin tek yolu bir medya oturumu açıp sesi "uzak" olarak
 * bildirmek. Bunu yapınca ses tuşları oturumun [VolumeProvider]'ına
 * düşüyor ve telefon kilitliyken de duyuluyor.
 *
 * Bir kısıtı var: sistem bize bas/bırak değil, yalnızca "ses artır/azalt"
 * olayı gönderiyor. Tuş basılı tutulduğunda bu olay tekrar tekrar geliyor,
 * bırakıldığında ise kesiliyor. Basılı tutmayı buradan çıkarıyoruz: ilk
 * olayda konuşma başlıyor, olaylar kesildikten [RELEASE_MS] sonra bitiyor.
 * Bu yüzden bıraktıktan sonra yarım saniyeye yakın bir kuyruk kalıyor.
 *
 * Ses azaltma tuşu bize gelse de olduğu gibi sisteme geçiriliyor: kullanıcı
 * karşı taraf kısık geldiğinde çaresiz kalmasın.
 */
class KeyPtt(
    private val ctx: Context,
    private val onKeyHeld: () -> Unit,
    private val onKeyReleased: () -> Unit
) {
    private companion object {
        /** Olaylar kesildikten bu kadar sonra konuşma bitmiş sayılıyor. */
        const val RELEASE_MS = 550L
        /** Tuş takılı kalırsa mikrofon sonsuza kadar açık kalmasın. */
        const val MAX_TX_MS = 60_000L
        const val POLL_MS = 80L
    }

    private var session: MediaSession? = null
    private var watchdog: Thread? = null

    @Volatile private var running = false
    @Volatile private var lastKeyAt = 0L
    @Volatile private var heldSince = 0L
    @Volatile private var holding = false

    @Volatile var available = false
        private set

    fun start() {
        if (running) return
        running = true
        try {
            val s = MediaSession(ctx, "telsiz-ptt")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            // Oturumun ses tuşlarını alabilmesi için etkin ve "çalıyor"
            // görünmesi gerekiyor.
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .build()
            )
            s.setPlaybackToRemote(object :
                VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    when {
                        direction > 0 -> onVolumeUp()
                        direction < 0 -> passThroughVolumeDown()
                    }
                }
            })
            s.isActive = true
            session = s
            available = true
        } catch (_: Exception) {
            // Bazı cihazlarda medya oturumu açılamıyor; ekran açıkken
            // çalışan yol bundan etkilenmiyor.
            available = false
            running = false
            return
        }

        watchdog = Thread({ watchLoop() }, "telsiz-keyptt").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        watchdog?.interrupt()
        watchdog = null
        if (holding) {
            holding = false
            try { onKeyReleased() } catch (_: Exception) {}
        }
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        available = false
    }

    private fun onVolumeUp() {
        lastKeyAt = System.currentTimeMillis()
        if (!holding) {
            holding = true
            heldSince = lastKeyAt
            try { onKeyHeld() } catch (_: Exception) {}
        }
    }

    private fun passThroughVolumeDown() {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
        }
    }

    private fun watchLoop() {
        while (running) {
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
            if (!running) return
            if (!holding) continue

            val now = System.currentTimeMillis()
            val quiet = now - lastKeyAt > RELEASE_MS
            val tooLong = now - heldSince > MAX_TX_MS
            if (quiet || tooLong) {
                holding = false
                try { onKeyReleased() } catch (_: Exception) {}
            }
        }
    }
}
