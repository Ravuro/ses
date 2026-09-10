package com.ravuro.telsiz

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Arayüz tamamen koddan kuruluyor: uygulama Android SDK'sı olmadan
 * derlendiği için kaynak (res/) dosyası yok.
 *
 * Tasarım yönü saha cihazı: telsiz dışarıda, elde, çoğu zaman ekrana
 * bakılmadan kullanılıyor. Bu yüzden ölçüm aleti dili — koyu zemin, tek
 * sinyal rengi, ölçüm değerleri için eşit genişlikte yazı.
 *
 * Renk kodu her yerde aynı: yeşil giden ses, camgöbeği gelen ses,
 * kehribar bekleyen bir durum, kırmızı hata.
 *
 * Ekran iki durumlu. Kapalıyken ayarlar görünür; açıldığında yerlerini
 * kanal, bağlantı telemetrisi ve kanaldakiler alır.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_MIC = 1
        const val REQ_LOCATION = 2

        const val BG = 0xFF0A0D12.toInt()
        const val SURFACE = 0xFF12161D.toInt()
        const val FIELD = 0xFF0E1219.toInt()
        const val RAISED = 0xFF171E27.toInt()
        const val LINE = 0xFF1D242E.toInt()
        const val LINE2 = 0xFF232B36.toInt()
        const val TEXT = 0xFFE9EDF3.toInt()
        const val TEXT2 = 0xFFB9C2CC.toInt()
        const val MUTED = 0xFF79838F.toInt()
        const val DIM = 0xFF5A6674.toInt()
        const val FAINT = 0xFF4C5764.toInt()
        const val GREEN = 0xFF3EE08A.toInt()
        const val GREEN_INK = 0xFF06170E.toInt()
        const val CYAN = 0xFF22D3EE.toInt()
        const val AMBER = 0xFFF5A524.toInt()
        const val RED = 0xFFFF5F5F.toInt()
    }

    private lateinit var prefs: Prefs

    // kurulum
    private lateinit var setupCard: View
    private lateinit var edtNick: EditText
    private lateinit var edtChannel: EditText
    private lateinit var volumeToggle: Toggle
    private lateinit var beepToggle: Toggle

    // canlı
    private lateinit var liveHero: View
    private lateinit var txtChannelBig: TextView
    private lateinit var txtWhoAmI: TextView
    private lateinit var telemetry: View
    private lateinit var telLanDot: View
    private lateinit var telLanValue: TextView
    private lateinit var telNetDot: View
    private lateinit var telNetValue: TextView
    private lateinit var telBridge: View
    private lateinit var telBridgeValue: TextView
    private lateinit var peersCard: View
    private lateinit var peersCount: TextView
    private lateinit var peersList: LinearLayout
    private lateinit var netCard: View
    private lateinit var txtDirect: TextView
    private lateinit var btnDirect: Button
    private lateinit var btnWifiSettings: Button

    private lateinit var statePill: View
    private lateinit var stateDot: View
    private lateinit var stateLabel: TextView
    private lateinit var btnPower: Button
    private lateinit var ptt: PttButton
    private lateinit var txtBanner: TextView
    private lateinit var txtHint: TextView

    private var service: TelsizService? = null
    private var bindRequested = false
    private var peersSignature = ""
    private val ui = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TelsizService.LocalBinder)?.service
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    // ---- yaşam döngüsü ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        prefs = Prefs(this)
        setContentView(buildUi())

        edtNick.setText(prefs.nick)
        edtChannel.setText(prefs.channel.toString())
    }

    override fun onStart() {
        super.onStart()
        bind()
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        service?.stopTx()   // ekrandan çıkarken tuş basılı kalmış olabilir
        unbind()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 300)
        }
    }

    // ---- ses tuşuyla bas-konuş ----
    //
    // Ses yükseltme tuşu bas-konuş; ses kısma tuşu ses ayarı olarak kalıyor ki
    // karşı taraf kısık geldiğinde çaresiz kalınmasın. Yalnızca uygulama
    // ekrandayken çalışır: arka planda tuşları sistem alıyor.

    private fun isPttKey(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPttKey(keyCode) && prefs.volumePtt && TelsizService.isRunning) {
            if (event.repeatCount == 0) {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                service?.startTx()
                refresh()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isPttKey(keyCode) && prefs.volumePtt && TelsizService.isRunning) {
            service?.stopTx()
            refresh()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---- arayüz parçaları ----

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Küçük ölçek etiketi: mono, seyrek, sessiz. */
    private fun caption(t: String, size: Float = 10f) = TextView(this).apply {
        text = t
        setTextColor(MUTED)
        textSize = size
        typeface = Fonts.mono(context, bold = true)
        letterSpacing = 0.18f
    }

    private fun body(t: String, size: Float = 13f, color: Int = DIM) = TextView(this).apply {
        text = t
        setTextColor(color)
        textSize = size
        typeface = Fonts.ui(context)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 18, LINE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun dot(size: Int, color: Int) = View(this).apply {
        background = circle(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun field(hint: String, type: Int, maxLen: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(FAINT)
        setTextColor(TEXT)
        textSize = 16f
        typeface = Fonts.ui(context)
        inputType = type
        setSingleLine(true)
        background = rounded(FIELD, 14, LINE2)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (maxLen > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
    }

    private fun outlineButton(t: String, color: Int, border: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = t
            setTextColor(color)
            textSize = 13f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.12f
            background = rounded(SURFACE, 16, border)
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        root.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            setPadding(dp(22), 0, dp(22), 0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(buildLiveHero())
        content.addView(buildTelemetry(), marginTop(0))
        content.addView(buildPeers(), marginTop(14))
        content.addView(buildSetup())
        content.addView(buildNetCard(), marginTop(12))

        btnPower = Button(this).apply {
            textSize = 15f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.14f
            stateListAnimator = null
            setOnClickListener {
                if (TelsizService.isRunning) stopSession() else requestAndStart()
            }
        }
        content.addView(btnPower, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(16); bottomMargin = dp(20) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Durum satırı — bas-konuşun hemen üstünde, göz oraya bakıyor
        txtBanner = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.16f
            setPadding(dp(16), 0, dp(16), dp(4))
            height = dp(24)
        }
        root.addView(txtBanner)

        ptt = PttButton(this).apply {
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!TelsizService.isRunning) {
                            toast("Önce BAŞLAT'a bas")
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            service?.startTx()
                            refresh()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        service?.stopTx()
                        refresh()
                        true
                    }
                    else -> false
                }
            }
        }
        val pttWrap = LinearLayout(this).apply { gravity = Gravity.CENTER }
        pttWrap.addView(ptt, LinearLayout.LayoutParams(dp(212), dp(212)))
        root.addView(pttWrap)

        txtHint = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Fonts.ui(context)
            setTextColor(DIM)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(30), dp(16), dp(30), dp(24))
        }
        root.addView(txtHint)

        return root
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "TELSİZ"
            setTextColor(MUTED)
            textSize = 11f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.22f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BG, 999, LINE2)
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        stateDot = dot(6, FAINT)
        pill.addView(stateDot)
        stateLabel = TextView(this).apply {
            textSize = 10f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.14f
            setTextColor(MUTED)
            setPadding(dp(7), 0, 0, 0)
        }
        pill.addView(stateLabel)
        statePill = pill
        header.addView(pill)
        return header
    }

    /** Kanal ekranın kimliği: buluşma noktası o, en büyük öğe o olmalı. */
    private fun buildLiveHero(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, dp(18))
        }
        txtChannelBig = TextView(this).apply {
            textSize = 50f
            typeface = Fonts.ui(context, bold = true)
            setTextColor(TEXT)
            letterSpacing = -0.02f
            includeFontPadding = false
        }
        row.addView(txtChannelBig)

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, dp(5))
        }
        side.addView(caption("KANAL"))
        txtWhoAmI = body("", 14f, TEXT2).apply { setPadding(0, dp(3), 0, 0) }
        side.addView(txtWhoAmI)
        row.addView(side)

        liveHero = row
        return row
    }

    /** Üç ölçüm kutusu: sorun çıktığında nereye bakılacağı belli olsun. */
    private fun buildTelemetry(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun box(label: String): Triple<View, View, TextView> {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE, 14, LINE)
                setPadding(dp(11), dp(12), dp(11), dp(12))
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val d = dot(5, MUTED)
            head.addView(d)
            head.addView(TextView(this).apply {
                text = label
                setTextColor(MUTED)
                textSize = 9f
                typeface = Fonts.mono(context, bold = true)
                letterSpacing = 0.12f
                setPadding(dp(6), 0, 0, 0)
            })
            b.addView(head)
            val v = TextView(this).apply {
                textSize = 12f
                typeface = Fonts.mono(context)
                setTextColor(TEXT)
                setPadding(0, dp(8), 0, 0)
            }
            b.addView(v)
            return Triple(b, d, v)
        }

        val (lanBox, lanDot, lanVal) = box("YEREL")
        val (netBox, netDot, netVal) = box("İNTERNET")
        val (brBox, _, brVal) = box("KÖPRÜ")
        telLanDot = lanDot; telLanValue = lanVal
        telNetDot = netDot; telNetValue = netVal
        telBridge = brBox; telBridgeValue = brVal

        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        row.addView(lanBox, lp().apply { rightMargin = dp(8) })
        row.addView(netBox, lp().apply { rightMargin = dp(8) })
        row.addView(brBox, lp())

        telemetry = row
        return row
    }

    private fun buildPeers(): View {
        val c = card().apply { setPadding(dp(16), dp(15), dp(16), dp(8)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(caption("KANALDA", 10f))
        peersCount = TextView(this).apply {
            textSize = 10f
            typeface = Fonts.mono(context, bold = true)
            setTextColor(GREEN)
            setPadding(dp(8), 0, 0, 0)
        }
        head.addView(peersCount)
        c.addView(head)

        peersList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        c.addView(peersList)
        peersCard = c
        return c
    }

    private fun buildSetup(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Kanal: eksi / büyük sayı / artı
        val chCard = card().apply { setPadding(dp(20), dp(18), dp(20), dp(20)) }
        chCard.addView(caption("KANAL"))
        val chRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        chRow.addView(stepButton("−") { nudgeChannel(-1) })
        edtChannel = EditText(this).apply {
            setTextColor(TEXT)
            textSize = 44f
            typeface = Fonts.ui(context, bold = true)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            background = null
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
        }
        chRow.addView(edtChannel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        chRow.addView(stepButton("+") { nudgeChannel(1) })
        chCard.addView(chRow)
        chCard.addView(body("Aynı kanaldaki herkes birbirini duyar.").apply {
            setPadding(0, dp(14), 0, 0)
        })
        wrap.addView(chCard)

        // Ad
        val nickWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        nickWrap.addView(caption("ADIN").apply { setPadding(0, 0, 0, dp(8)) })
        edtNick = field("Adın", InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 16)
        nickWrap.addView(edtNick, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        wrap.addView(nickWrap)

        volumeToggle = toggleRow(
            "Ses tuşuyla konuş",
            "Ses yükseltme tuşunu basılı tut —\nekran kapalıyken de",
            { prefs.volumePtt },
            { prefs.volumePtt = !prefs.volumePtt }
        )
        wrap.addView(volumeToggle.row, marginTop(14))

        beepToggle = toggleRow(
            "Telsiz bipi",
            "Konuşman bitince karşı taraf bip duyar",
            { prefs.beep },
            { prefs.beep = !prefs.beep }
        )
        wrap.addView(beepToggle.row, marginTop(10))

        setupCard = wrap
        return wrap
    }

    /** Aç/kapa anahtarı: iki ayar için de aynı görünüm. */
    private inner class Toggle(
        val row: View,
        private val track: LinearLayout,
        private val knob: View,
        private val get: () -> Boolean
    ) {
        fun refresh() {
            val on = get()
            track.background = rounded(
                if (on) 0xFF1B7F4E.toInt() else RAISED, 999,
                if (on) 0xFF2C9E67.toInt() else LINE2
            )
            track.gravity = (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            knob.background = circle(if (on) GREEN else 0xFF3B4756.toInt())
        }
    }

    private fun toggleRow(
        title: String,
        subtitle: String,
        get: () -> Boolean,
        toggle: () -> Unit
    ): Toggle {
        val cardView = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(TEXT)
            textSize = 15f
            typeface = Fonts.ui(context)
        })
        texts.addView(body(subtitle, 12f, DIM).apply { setPadding(0, dp(3), 0, 0) })
        cardView.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val track = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val knob = View(this)
        track.addView(knob, LinearLayout.LayoutParams(dp(24), dp(24)))
        cardView.addView(track, LinearLayout.LayoutParams(dp(50), dp(30)))

        val t = Toggle(cardView, track, knob, get)
        cardView.setOnClickListener {
            toggle()
            t.refresh()
        }
        t.refresh()
        return t
    }

    private fun stepButton(glyph: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        setTextColor(TEXT2)
        textSize = 20f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        background = rounded(RAISED, 12, LINE2)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun nudgeChannel(delta: Int) {
        val cur = edtChannel.text.toString().toIntOrNull() ?: 1
        val next = (cur + delta).coerceIn(1, 999)
        edtChannel.setText(next.toString())
        edtChannel.setSelection(edtChannel.text.length)
    }

    private fun buildNetCard(): View {
        val c = card()
        c.addView(caption("AĞ"))
        txtDirect = body("", 13f, TEXT2).apply { setPadding(0, dp(12), 0, dp(14)) }
        c.addView(txtDirect)
        btnDirect = outlineButton("TELSİZ AĞI KUR", TEXT2, LINE2) { toggleDirect() }
        c.addView(btnDirect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ))
        btnWifiSettings = outlineButton("WiFi AYARLARINI AÇ", TEXT2, LINE2) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }
        c.addView(btnWifiSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(9) })
        netCard = c
        return c
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- oturum ----

    private fun requestAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (granted) startSession()
            else Toast.makeText(this, "Mikrofon izni olmadan telsiz çalışmaz", Toast.LENGTH_LONG).show()

            REQ_LOCATION -> if (granted) service?.createDirectGroup()
            else Toast.makeText(
                this,
                "Android, Wi-Fi Direct için konum izni istiyor. İzin vermezsen telsiz ağı kurulamaz.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startSession() {
        prefs.nick = edtNick.text.toString().trim()
        prefs.channel = (edtChannel.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
        edtChannel.setText(prefs.channel.toString())

        startForegroundService(Intent(this, TelsizService::class.java))
        bind()
        refresh()
    }

    private fun stopSession() {
        service?.stopTx()
        unbind()
        stopService(Intent(this, TelsizService::class.java))
        peersSignature = ""
        refresh()
    }

    private fun toggleDirect() {
        val svc = service
        if (svc == null) {
            toast("Önce BAŞLAT'a bas")
            return
        }
        if (svc.directActive) {
            svc.removeDirectGroup()
            return
        }
        if (!svc.directSupported()) {
            toast("Bu cihaz Wi-Fi Direct desteklemiyor — biri hotspot açsın")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        svc.createDirectGroup()
    }

    private fun bind() {
        if (bindRequested) return
        bindRequested = try {
            bindService(Intent(this, TelsizService::class.java), connection, 0)
        } catch (_: Exception) {
            false
        }
    }

    private fun unbind() {
        if (!bindRequested) return
        bindRequested = false
        service = null
        try { unbindService(connection) } catch (_: Exception) {}
    }

    // ---- tazeleme ----

    private fun refresh() {
        val running = TelsizService.isRunning
        val svc = service

        setupCard.visibility = if (running) View.GONE else View.VISIBLE
        liveHero.visibility = if (running) View.VISIBLE else View.GONE
        telemetry.visibility = if (running) View.VISIBLE else View.GONE
        peersCard.visibility = if (running) View.VISIBLE else View.GONE
        netCard.visibility = if (running) View.VISIBLE else View.GONE

        ptt.live = running
        ptt.transmitting = svc?.transmitting == true
        ptt.receiving = running && svc != null && svc.talkingNow().isNotEmpty()

        if (!running) {
            stateDot.background = circle(FAINT)
            stateLabel.text = "KAPALI"
            stateLabel.setTextColor(MUTED)
            btnPower.text = "BAŞLAT"
            btnPower.background = rounded(GREEN, 16)
            btnPower.setTextColor(GREEN_INK)
            txtBanner.text = ""
            txtHint.text = svc?.startError ?: "Başlatınca mikrofon izni istenir"
            return
        }

        stateDot.background = circle(GREEN)
        stateLabel.text = "YAYINDA"
        stateLabel.setTextColor(TEXT2)
        btnPower.text = "DURDUR"
        btnPower.background = rounded(SURFACE, 16, 0xFF3A2529.toInt())
        btnPower.setTextColor(RED)

        if (svc == null) {
            txtHint.text = "Başlatılıyor…"
            bind()
            return
        }

        txtChannelBig.text = String.format("%02d", svc.currentChannel())
        txtWhoAmI.text = svc.currentNick() + " olarak bağlısın"

        // Telemetri
        val lanOk = svc.lanError == null && svc.lanIp != "-"
        telLanDot.background = circle(if (lanOk) GREEN else RED)
        telLanValue.text = if (lanOk) svc.lanIp else "yok"
        telLanValue.setTextColor(if (lanOk) TEXT else RED)

        val rtt = svc.relayRttMs
        when {
            !svc.relayEnabled -> {
                telNetDot.background = circle(FAINT)
                telNetValue.text = "kapalı"
                telNetValue.setTextColor(DIM)
            }
            svc.relayConnected -> {
                telNetDot.background = circle(GREEN)
                telNetValue.text = if (rtt >= 0) "$rtt ms" else "bağlı"
                telNetValue.setTextColor(TEXT)
            }
            else -> {
                telNetDot.background = circle(AMBER)
                telNetValue.text = "bağlanıyor"
                telNetValue.setTextColor(AMBER)
            }
        }

        telBridge.visibility = if (svc.bridging) View.VISIBLE else View.INVISIBLE
        telBridgeValue.text = "açık"
        telBridgeValue.setTextColor(CYAN)

        refreshPeers(svc)
        refreshNetCard(svc)

        val talking = svc.talkingNow()
        when {
            svc.transmitting -> {
                txtBanner.text = "MİKROFON AÇIK"
                txtBanner.setTextColor(GREEN)
            }
            talking.isNotEmpty() -> {
                txtBanner.text = talking.joinToString(", ").uppercase() + " KONUŞUYOR"
                txtBanner.setTextColor(CYAN)
            }
            else -> txtBanner.text = ""
        }

        txtHint.text = when {
            !prefs.volumePtt -> "Konuşmak için düğmeyi basılı tut"
            svc.keyPttReady -> {
                val n = svc.keyPttEvents
                "Düğmeyi ya da ses yükseltme tuşunu basılı tut\n" +
                    if (n > 0) "Ekran kapalıyken de çalışıyor · $n olay"
                    else "Ekran kapalıyken de çalışmalı — deneyip buraya bak"
            }
            else -> "Düğmeyi ya da ses yükseltme tuşunu basılı tut"
        }
    }

    /**
     * Kanaldakiler. Liste her 300 ms'de yeniden kurulmasın diye önce imzası
     * karşılaştırılıyor; yoksa dokunmaya çalışırken satırlar altından kayar.
     */
    private fun refreshPeers(svc: TelsizService) {
        val peers = svc.peerList()
        val talking = svc.talkingNow().toSet()
        val sig = peers.joinToString("|") { it.nick + (if (talking.contains(it.nick)) "*" else "") }
        peersCount.text = peers.size.toString()
        if (sig == peersSignature) return
        peersSignature = sig

        peersList.removeAllViews()
        if (peers.isEmpty()) {
            peersList.addView(body("Kanalda başka kimse yok.", 14f, DIM).apply {
                setPadding(0, 0, 0, dp(8))
            })
            return
        }

        for ((i, p) in peers.withIndex()) {
            val speaking = talking.contains(p.nick)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            row.addView(TextView(this).apply {
                text = initials(p.nick)
                setTextColor(TEXT2)
                textSize = 12f
                typeface = Fonts.ui(context, bold = true)
                gravity = Gravity.CENTER
                background = rounded(0xFF1D2530.toInt(), 10, 0xFF2A3542.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })
            row.addView(TextView(this).apply {
                text = p.nick
                setTextColor(TEXT)
                textSize = 15f
                typeface = Fonts.ui(context)
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (speaking) "KONUŞUYOR" else "dinliyor"
                setTextColor(if (speaking) CYAN else DIM)
                textSize = 10f
                typeface = Fonts.mono(context, bold = speaking)
                letterSpacing = 0.08f
            })
            peersList.addView(row)

            if (i < peers.size - 1) {
                peersList.addView(View(this).apply {
                    setBackgroundColor(0xFF171E27.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    )
                })
            }
        }
        peersList.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
        })
    }

    private fun initials(nick: String): String {
        val t = nick.trim()
        if (t.isEmpty()) return "??"
        val parts = t.split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        } else {
            t.take(2).uppercase()
        }
    }

    /**
     * Ağ kartı. "İnternet yokken ne olacak" sorusunun cevabı burada, duruma
     * göre tek bir sonraki adım olarak.
     */
    private fun refreshNetCard(svc: TelsizService) {
        val ssid = svc.directSsid
        if (ssid != null) {
            btnDirect.text = "AĞI KAPAT"
            txtDirect.text = buildString {
                append("Kendi ağını yayınlıyorsun — internet gerekmiyor.\n\n")
                append("Ağ adı   ").append(ssid).append('\n')
                append("Parola   ").append(svc.directPassphrase ?: "-").append('\n')
                append("Bağlı    ").append(svc.directClients).append(" cihaz\n\n")
                append("Diğerleri WiFi ayarlarından bu ağa bağlansın. ")
                append("Menzil ~50-100 m; telefon yüksekteyken daha iyi.")
                append(bridgeNote(svc))
            }
            return
        }

        btnDirect.text = "TELSİZ AĞI KUR"
        val lanOk = svc.lanError == null && svc.lanIp != "-"
        txtDirect.text = when {
            lanOk -> "Yerel ağdasın. Aynı ağdakilerle internet olmadan " +
                "konuşabilirsin." + bridgeNote(svc) +
                "\n\nAğ yoksa \"TELSİZ AĞI KUR\" ile kendi ağını yayınla."
            !svc.directSupported() ->
                "Ağ yok. Bu cihaz Wi-Fi Direct desteklemiyor — biri " +
                    "telefonundan hotspot açsın, diğerleri bağlansın."
            svc.directState != "kapalı" && svc.directState != "ağ açık" ->
                "Ağ yok.\n" + svc.directState + "\n\nWiFi'nin açık olduğundan emin ol."
            else ->
                "Ağ yok — ne WiFi ne hotspot.\n\n\"TELSİZ AĞI KUR\"a bas: " +
                    "telefonun kendi kablosuz ağını yayınlar, diğerleri ona " +
                    "bağlanır. İnternet gerekmez. (WiFi açık olmalı, bağlı " +
                    "olması gerekmiyor.)"
        }
    }

    private fun bridgeNote(svc: TelsizService): String =
        if (!svc.bridging) ""
        else "\n\nKöprü açık: bu telefon grubu internete bağlıyor, " +
            svc.bridged + " paket aktarıldı."
}
