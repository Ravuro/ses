package com.ravuro.telsiz

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Ekran kapalıyken bas-konuş.
 *
 * Ekran kapandığında tuş olayları uygulamalara hiç ulaşmıyor: pencere
 * yöneticisi ses tuşlarını kendisi tüketip "kullanıcıya iletilmedi" diye
 * işaretliyor. Bu yüzden erişilebilirlik servisi de ([KeyService]) ekran
 * kapalıyken devreye giremiyor — kilit ekranında (ekran açık) çalışıp
 * karanlıkta susmasının sebebi bu.
 *
 * Karanlıkta geriye tek yol kalıyor: medya oturumu açıp sesi "uzak" olarak
 * bildirmek. O zaman ses tuşları oturumun [VolumeProvider]'ına düşüyor.
 * Ama bu, sistemin ses tuşlarını "şu an çalan" oturuma yönlendirmesine
 * bağlı ve bazı üretici arayüzleri bunu yapmıyor.
 *
 * İki dayanak noktası var:
 *
 *  - **Ses tuşu**: sistem bas/bırak değil yalnızca "ses artır" olayı
 *    gönderiyor. Basılı tutmayı buradan çıkarıyoruz; bırakıldıktan sonra
 *    [RELEASE_MS] kadar kuyruk kalıyor.
 *  - **Kulaklık düğmesi**: medya tuşları oturuma doğrudan geliyor ve ekran
 *    kapalıyken de güvenilir çalışıyor. Kulaklık düğmeleri çoğunlukla tek
 *    tık gönderdiği için burada aç/kapa mantığı kullanılıyor: bir bas
 *    konuşmaya başla, bir daha bas bitir.
 */
class KeyPtt(
    private val ctx: Context,
    private val onKeyHeld: () -> Unit,
    private val onKeyReleased: () -> Unit
) {
    private companion object {
        /** Ses tuşu olayları kesildikten bu kadar sonra konuşma biter. */
        const val RELEASE_MS = 550L
        /** Tuş takılı kalırsa mikrofon sonsuza kadar açık kalmasın. */
        const val MAX_TX_MS = 60_000L
        /** Oturum durumunu bu aralıkla tazele. */
        const val STATE_REFRESH_MS = 8_000L
        const val POLL_MS = 80L
    }

    /** Konuşmayı hangi tuş başlattı? Bırakma kuralları buna göre. */
    private enum class Source { NONE, VOLUME, MEDIA }

    private var session: MediaSession? = null
    private var watchdog: Thread? = null

    @Volatile private var running = false
    @Volatile private var source = Source.NONE
    @Volatile private var lastKeyAt = 0L
    @Volatile private var heldSince = 0L
    private var startedAt = 0L
    private var lastStateAt = 0L

    @Volatile var available = false
        private set

    /**
     * Sisteme kaç kez tuş olayı geldiği. Ekran kapalıyken bunun artıp
     * artmadığı, yolun çalışıp çalışmadığını söyleyen tek işaret.
     */
    @Volatile var events: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        startedAt = System.currentTimeMillis()

        try {
            val s = MediaSession(ctx, "telsiz-ptt")
            s.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        ?: return false
                    return onMediaKey(ev)
                }
            })
            // Künyesi ve geri çağırımı olmayan oturumu bazı cihazlar gerçek
            // saymıyor ve tuşları ona yönlendirmiyor.
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Telsiz")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Kanal açık")
                    .build()
            )
            s.setPlaybackToRemote(object :
                VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    events++
                    when {
                        direction > 0 -> onVolumeUp()
                        direction < 0 -> passThroughVolumeDown()
                    }
                }
            })
            s.isActive = true
            session = s
            pushPlaybackState()
            available = true
        } catch (_: Exception) {
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
        release()
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        available = false
    }

    // ---- tuşlar ----

    private fun onVolumeUp() {
        lastKeyAt = System.currentTimeMillis()
        if (source == Source.NONE) {
            source = Source.VOLUME
            heldSince = lastKeyAt
            fire(onKeyHeld)
        }
    }

    /** Kulaklık düğmesi: tek tık geldiği için aç/kapa. */
    private fun onMediaKey(ev: KeyEvent): Boolean {
        val ilgili = when (ev.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
        if (!ilgili) return false
        // Bırakma olayını sessizce yutuyoruz; aç/kapa basışta oluyor.
        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return true

        events++
        if (source == Source.MEDIA) {
            release()
        } else if (source == Source.NONE) {
            source = Source.MEDIA
            heldSince = System.currentTimeMillis()
            lastKeyAt = heldSince
            fire(onKeyHeld)
        }
        return true
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

    private fun release() {
        if (source == Source.NONE) return
        source = Source.NONE
        fire(onKeyReleased)
    }

    private fun fire(f: () -> Unit) {
        try { f() } catch (_: Exception) {}
    }

    // ---- gözcü ----

    private fun watchLoop() {
        while (running) {
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
            if (!running) return

            val now = System.currentTimeMillis()

            // Oturum durumu tazelenmezse bazı sistemler onu "artık çalmıyor"
            // sayıp ses tuşlarını başka yere yönlendiriyor.
            if (now - lastStateAt >= STATE_REFRESH_MS) pushPlaybackState()

            when (source) {
                Source.VOLUME ->
                    // Ses tuşunda bırakma olayı yok: olaylar kesilince bitir.
                    if (now - lastKeyAt > RELEASE_MS) release()
                Source.MEDIA ->
                    // Aç/kapa: yalnızca emniyet süresi geçerli.
                    if (now - heldSince > MAX_TX_MS) release()
                Source.NONE -> {}
            }
            if (source != Source.NONE && now - heldSince > MAX_TX_MS) release()
        }
    }

    private fun pushPlaybackState() {
        val s = session ?: return
        lastStateAt = System.currentTimeMillis()
        try {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        // İlerleyen bir konum: duran konum "çalmıyor" gibi
                        // yorumlanabiliyor.
                        System.currentTimeMillis() - startedAt,
                        1f
                    )
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE
                    )
                    .build()
            )
            if (!s.isActive) s.isActive = true
        } catch (_: Exception) {
        }
    }
}
