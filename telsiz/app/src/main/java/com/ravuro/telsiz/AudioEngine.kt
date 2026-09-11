package com.ravuro.telsiz

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mikrofon yakalama + gelen seslerin çalınması.
 *
 * Çalma tarafı gönderen başına ayrı bir jitter tamponu tutar ve aynı anda
 * konuşan birden fazla kişiyi toplayarak karıştırır — gerçek bir telsizde
 * sesler üst üste biner, burada da öyle.
 */
class AudioEngine(private val onFrame: (ByteArray, Int, Int, Int) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 640          // 40 ms
        private const val MAX_QUEUED = 25      // ~1 sn: gecikme sınırı
        // 3 kare = 120 ms. 2 kare yetmiyordu: tek bir geciken paket kuyruğu
        // boşaltıp oynatmayı kesiyor, kelimenin ortasında boşluk açılıyordu.
        private const val PRIME_FRAMES = 3

        // Otomatik kazanç: mikrofon çoğu telefonda konuşma için fazla kısık
        // geliyor. Sabit bir çarpan kimine az kimine çok gelirdi; bunun yerine
        // tepe değeri izleyip hedefe yaklaştırıyoruz.
        private const val AGC_TARGET = 20000f
        private const val AGC_MAX = 8f
        private const val AGC_ATTACK = 0.5f    // kazancı düşürmek hızlı
        private const val AGC_RELEASE = 0.06f  // yükseltmek yavaş
        private const val AGC_FLOOR = 250      // bunun altı sessizlik sayılır
        private const val LIMIT_KNEE = 26000f

        /** Tekrar dinleme için saklanan en uzun süre. */
        private const val REPLAY_MAX_SEC = 20
        /** Bu kadar sessizlikten sonra konuşma bitmiş sayılır. */
        private const val SEGMENT_GAP_MS = 900L
        /** Tekrar çalınan ses bu sahte gönderenin kuyruğundan geçiyor. */
        private const val REPLAY_SENDER = 0L
    }

    private class SenderQueue {
        val frames = ConcurrentLinkedQueue<ShortArray>()
        @Volatile var primed = false

        fun offer(f: ShortArray) {
            frames.add(f)
            while (frames.size > MAX_QUEUED) frames.poll()
            if (frames.size >= PRIME_FRAMES) primed = true
        }

        fun size(): Int = frames.size

        fun poll(): ShortArray? {
            if (!primed) return null
            val f = frames.poll()
            if (f == null) primed = false
            return f
        }
    }

    private val queues = ConcurrentHashMap<Long, SenderQueue>()

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var txThread: Thread? = null
    private var rxThread: Thread? = null

    /** Kodlayıcı durumu kareler boyunca akıyor; her paket başlangıcını taşıyor. */
    private val encState = Adpcm.State()
    private var agcGain = 1f

    // ---- kaçırılanı tekrar dinleme ----
    //
    // Gelen ses, konuşma parçalarına ayrılarak saklanıyor. Bir konuşma
    // bittiğinde (araya SEGMENT_GAP_MS sessizlik girince) o parça kenara
    // konuyor ve tek düğmeyle tekrar çalınabiliyor. Gürültülü ortamda en
    // çok işe yarayan şey bu: "ne dedi?" sorusunun cevabı.
    private val replayLock = Object()
    // Halka tampon: konuşma sınırı aşarsa baştan atıp sonu tutuyor.
    // "Ne dedi?" diye sorulduğunda istenen şey konuşmanın sonu.
    private val recBuf = ShortArray(SAMPLE_RATE * REPLAY_MAX_SEC)
    private var recStart = 0
    private var recLen = 0
    private var lastVoiceAt = 0L
    private var savedSegment: ShortArray? = null

    @Volatile var replaySeconds: Int = 0
        private set
    val replayAvailable: Boolean get() = replaySeconds > 0

    @Volatile private var running = false
    @Volatile var transmitting = false
        private set

    /** Konuşurken hoparlörü kapat — hoparlör/mikrofon geri beslemesini keser. */
    @Volatile var halfDuplex = true

    @Volatile var lastError: String? = null
        private set

    /**
     * Ses iş parçacıkları hâlâ dönüyor mu. Mikrofon başka bir uygulamaya
     * kaptırıldığında ya da beklenmedik bir hata düştüğünde döngüler
     * ölüyor; nabız buna bakıp motoru yeniden kuruyor.
     */
    val alive: Boolean
        get() = running && txThread?.isAlive == true && rxThread?.isAlive == true

    /**
     * Miksere en son ne zaman kare yazıldı.
     *
     * "İş parçacığı yaşıyor" yetmiyor. AudioTrack tıkandığında write()
     * süresiz blokluyor: iş parçacığı canlı görünüyor, [alive] true diyor,
     * ama tek bir örnek bile çalmıyor. Gelen ses kuyrukta birikiyor ve
     * uygulama öne alınınca hepsi birden boşalıyor — dışarıdan "arkaplanda
     * ses gelmiyor, açınca geliyor" olarak görünen şey bu.
     *
     * Bu yüzden canlılık değil ilerleme ölçülüyor.
     */
    @Volatile var lastWriteAt = 0L
        private set

    /** Mikserin yazdığı toplam kare. Tanıda ilerlemeyi göstermek için. */
    @Volatile var framesWritten = 0L
        private set

    /** Kaç milisaniyedir tek kare yazılmadı. Normalde 40 ms'de bir yazılır. */
    val stalledMs: Long
        get() = if (lastWriteAt == 0L) 0L else System.currentTimeMillis() - lastWriteAt

    /** Çalınmayı bekleyen kare sayısı — tıkanma birikmeyle birlikte gelir. */
    val queuedFrames: Int
        get() {
            var n = 0
            for (q in queues.values) n += q.size()
            return n
        }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        try {
            val recMin = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(recMin, FRAME_SAMPLES * 2 * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "mikrofon açılamadı"
                rec.release()
                return false
            }
            attachEffects(rec.audioSessionId)
            record = rec

            val playMin = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(playMin, FRAME_SAMPLES * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
        } catch (e: Exception) {
            lastError = e.message ?: "ses açılamadı"
            release()
            return false
        }

        running = true
        lastError = null
        lastWriteAt = System.currentTimeMillis()
        // Ses döngüleri gerçek zamanlı: 40 ms'de bir kare yetiştirmeleri
        // gerekiyor. Varsayılan öncelikte, arkaplandaki bir uygulamanın
        // iş parçacıkları saniyelerce sıraya alınabiliyor ve bu, çıkışın
        // tıkanmasından ayırt edilemiyor. Android'in ses için ayırdığı
        // öncelik tam olarak bunun içindir.
        txThread = Thread({
            raiseAudioPriority()
            captureLoop()
        }, "telsiz-tx").apply { isDaemon = true; start() }
        rxThread = Thread({
            raiseAudioPriority()
            playbackLoop()
        }, "telsiz-rx").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        transmitting = false
        txThread?.interrupt()
        rxThread?.interrupt()
        txThread = null
        rxThread = null
        try { record?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        release()
        queues.clear()
        synchronized(replayLock) {
            recLen = 0
            recStart = 0
            savedSegment = null
            replaySeconds = 0
        }
    }

    fun startTx() {
        val rec = record ?: return
        if (transmitting) return
        try {
            rec.startRecording()
            // Yeni konuşma yeni bir akış: kodlayıcı ve kazanç sıfırdan.
            encState.reset()
            agcGain = 1f
            transmitting = true
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    private fun raiseAudioPriority() {
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
        } catch (_: Exception) {
            // Öncelik verilemezse çalışmaya devam; sadece daha kırılgan olur.
        }
    }

    fun stopTx() {
        if (!transmitting) return
        transmitting = false
        try { record?.stop() } catch (_: Exception) {}
    }

    /** Ağdan gelen ADPCM parçasını çözüp o gönderenin kuyruğuna koyar. */
    fun enqueue(senderId: Long, data: ByteArray, off: Int, len: Int, predictor: Int, index: Int) {
        val pcm = ShortArray(len * 2)
        val n = Adpcm.decode(data, off, len, pcm, predictor, index)
        if (n <= 0) return
        val frame = if (n == pcm.size) pcm else pcm.copyOf(n)
        record(frame, n)
        queues.getOrPut(senderId) { SenderQueue() }.offer(frame)
    }

    /** Gelen sesi, tekrar dinlenebilmesi için parçalara ayırarak biriktirir. */
    private fun record(frame: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        synchronized(replayLock) {
            // Araya uzun sessizlik girdiyse önceki konuşma bitmiştir.
            if (recLen > 0 && now - lastVoiceAt > SEGMENT_GAP_MS) closeSegment()
            lastVoiceAt = now
            for (i in 0 until n) {
                recBuf[(recStart + recLen) % recBuf.size] = frame[i]
                if (recLen < recBuf.size) {
                    recLen++
                } else {
                    recStart = (recStart + 1) % recBuf.size
                }
            }
        }
    }

    /** replayLock tutulurken çağrılır. Halkayı düz diziye açar. */
    private fun closeSegment() {
        if (recLen <= 0) return
        val seg = ShortArray(recLen)
        for (i in 0 until recLen) seg[i] = recBuf[(recStart + i) % recBuf.size]
        savedSegment = seg
        replaySeconds = maxOf(1, recLen / SAMPLE_RATE)
        recLen = 0
        recStart = 0
    }

    /** Son konuşmayı yeniden çalar. Canlı ses gelirse üstüne karışır. */
    fun replayLast(): Boolean {
        val seg = synchronized(replayLock) {
            // Konuşma henüz kapanmadıysa (hemen sonra basıldıysa) şimdi kapat.
            if (recLen > 0 && System.currentTimeMillis() - lastVoiceAt > SEGMENT_GAP_MS) {
                closeSegment()
            }
            savedSegment
        } ?: return false

        val q = queues.getOrPut(REPLAY_SENDER) { SenderQueue() }
        var off = 0
        while (off < seg.size) {
            val n = minOf(FRAME_SAMPLES, seg.size - off)
            val f = ShortArray(FRAME_SAMPLES)
            System.arraycopy(seg, off, f, 0, n)
            q.offer(f)
            off += n
        }
        return true
    }

    /**
     * Hazır PCM'i (bip) gönderenin kuyruğuna koyar. Sesle aynı kuyruğu
     * kullanıyor: bitiş bipi böyle kendiliğinden son sözden sonra çalıyor.
     */
    fun enqueuePcm(senderId: Long, pcm: ShortArray) {
        val q = queues.getOrPut(senderId) { SenderQueue() }
        var off = 0
        while (off < pcm.size) {
            val n = minOf(FRAME_SAMPLES, pcm.size - off)
            val frame = ShortArray(FRAME_SAMPLES)
            System.arraycopy(pcm, off, frame, 0, n)
            q.offer(frame)
            off += n
        }
    }

    fun forget(senderId: Long) {
        queues.remove(senderId)
    }

    private fun captureLoop() {
        val pcm = ShortArray(FRAME_SAMPLES)
        val enc = ByteArray(FRAME_SAMPLES / 2 + 8)
        // Üst üste okuma hatası mikrofonun elimizden gittiğini gösteriyor.
        // Sonsuza kadar denemek yerine çıkıyoruz ki nabız motoru yeniden
        // kursun; yoksa telsiz açık görünüp hiç ses göndermiyor.
        var readFails = 0
        while (running) {
            if (!transmitting) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return }
                continue
            }
            val rec = record ?: return
            val r = try {
                rec.read(pcm, 0, FRAME_SAMPLES)
            } catch (_: Exception) {
                -1
            }
            if (r > 0) readFails = 0
            if (r > 0 && transmitting) {
                applyGain(pcm, r)
                // Paket, kodlamadan ÖNCEKİ durumu taşımalı ki karşı taraf
                // aynı noktadan başlasın.
                val p0 = encState.predictor
                val i0 = encState.index
                val n = Adpcm.encode(pcm, r, enc, encState)
                onFrame(enc, n, p0, i0)
            } else if (r <= 0) {
                // Yalnızca konuşurken sayıyoruz: beklerken sıfır dönmesi
                // olağan, hata değil.
                if (r < 0 && transmitting && ++readFails >= 50) {
                    lastError = "mikrofon okunamıyor ($r)"
                    return
                }
                try { Thread.sleep(10) } catch (_: InterruptedException) { return }
            }
        }
    }

    /**
     * Tepe izleyen kazanç + yumuşak sınırlama. Kodlamadan önce uygulanıyor:
     * ADPCM'in hata payı sinyal seviyesine göreli, dolayısıyla sinyali
     * yükseltmek yalnızca sesi açmakla kalmıyor, kaliteyi de artırıyor.
     */
    private fun applyGain(pcm: ShortArray, n: Int) {
        var peak = 0
        for (i in 0 until n) {
            val a = if (pcm[i] < 0) -pcm[i].toInt() else pcm[i].toInt()
            if (a > peak) peak = a
        }

        if (peak > AGC_FLOOR) {
            val desired = (AGC_TARGET / peak).coerceIn(1f, AGC_MAX)
            val rate = if (desired < agcGain) AGC_ATTACK else AGC_RELEASE
            agcGain += (desired - agcGain) * rate
        }
        if (agcGain <= 1.001f) return

        for (i in 0 until n) {
            var v = pcm[i] * agcGain
            // Tepe noktalarını kesmek yerine 4:1 sıkıştır: sert kırpma
            // duyulur bir çatırtı yapıyor.
            if (v > LIMIT_KNEE) v = LIMIT_KNEE + (v - LIMIT_KNEE) * 0.25f
            else if (v < -LIMIT_KNEE) v = -LIMIT_KNEE + (v + LIMIT_KNEE) * 0.25f
            pcm[i] = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v.toInt().toShort()
            }
        }
    }

    private fun playbackLoop() {
        val mix = IntArray(FRAME_SAMPLES)
        val out = ShortArray(FRAME_SAMPLES)
        while (running) {
            var active = 0
            java.util.Arrays.fill(mix, 0)

            for (q in queues.values) {
                val f = q.poll() ?: continue
                active++
                val n = minOf(f.size, FRAME_SAMPLES)
                for (i in 0 until n) mix[i] += f[i].toInt()
            }

            // Boştayken bile sessizlik yazıyoruz, iki sebeple:
            //
            // 1) Akış canlı kalıyor. Boşta yazmayı bırakınca AudioTrack aç
            //    kalıyor ve konuşma başlarken kesik bir çıtırtı oluyordu.
            // 2) Android ses tuşlarını "şu an ses çalan" uygulamaya
            //    yönlendiriyor. Arada susarsak sistem bizi çalmıyor sayıp
            //    tuşları vermiyor — ekran kapalıyken bas-konuş bu yüzden
            //    çalışmıyordu.
            //
            // Konuşurken de sessizlik yazılıyor: hoparlör mikrofona kaçmasın
            // diye gelen ses çalınmıyor ama akış kesilmiyor.
            // Konuşma bitişini burada yakalıyoruz: paket gelmeyi kesince
            // record() bir daha çağrılmıyor, dolayısıyla parçayı kapatacak
            // başka bir yer yok.
            if (active == 0) {
                val now = System.currentTimeMillis()
                synchronized(replayLock) {
                    if (recLen > 0 && now - lastVoiceAt > SEGMENT_GAP_MS) closeSegment()
                }
            }

            val mute = active == 0 || (halfDuplex && transmitting)
            if (mute) {
                java.util.Arrays.fill(out, 0)
            } else {
                for (i in 0 until FRAME_SAMPLES) {
                    var v = mix[i]
                    if (v > 32767) v = 32767
                    if (v < -32768) v = -32768
                    out[i] = v.toShort()
                }
            }
            try {
                // write() bloklayarak döngüyü gerçek zamana göre hızlandırıyor;
                // ayrıca uyumaya gerek kalmıyor.
                val wrote = track?.write(out, 0, FRAME_SAMPLES) ?: return
                if (wrote < 0) {
                    // Negatif dönüş AudioTrack'in öldüğü anlamına geliyor
                    // (ERROR_DEAD_OBJECT ve arkadaşları). Döngüyü sürdürmek
                    // en kötü seçenek: write artık bloklamadığı için döngü
                    // saniyede on binlerce tur atıp işlemciyi yakıyor ve ses
                    // yine gelmiyor. Çıkıyoruz — [alive] düşünce nabız motoru
                    // yeniden kuruyor.
                    lastError = "ses çıkışı düştü ($wrote)"
                    return
                }
                framesWritten++
                lastWriteAt = System.currentTimeMillis()
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun attachEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {}
    }

    private fun release() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        try { record?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        record = null
        track = null
    }
}
