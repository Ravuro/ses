package com.ravuro.telsiz

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_MIC = 1
        const val REQ_LOCATION = 2

        const val BG = 0xFF0E1116.toInt()
        const val PANEL = 0xFF171C24.toInt()
        const val FIELD = 0xFF0B0E13.toInt()
        const val BORDER = 0xFF2A313D.toInt()
        const val TEXT = 0xFFE6EAF0.toInt()
        const val MUTED = 0xFF8A94A6.toInt()
        const val ACCENT = 0xFF3DDC84.toInt()
        const val PTT = 0xFF1F6FEB.toInt()
    }

    private lateinit var prefs: Prefs

    private lateinit var edtNick: EditText
    private lateinit var edtChannel: EditText
    private lateinit var edtRelay: EditText
    private lateinit var btnPower: Button
    private lateinit var btnPtt: Button
    private lateinit var txtNet: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtPeers: TextView
    private lateinit var txtTalking: TextView
    private lateinit var txtDirect: TextView
    private lateinit var btnDirect: Button
    private lateinit var btnWifiSettings: Button

    private var service: TelsizService? = null
    private var bindRequested = false
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

        prefs = Prefs(this)
        setContentView(buildUi())

        edtNick.setText(prefs.nick)
        edtChannel.setText(prefs.channel.toString())
        edtRelay.setText(prefs.relayUrl)
    }

    override fun onStart() {
        super.onStart()
        bind()
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        unbind()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 400)
        }
    }

    // ---- arayüz kurulumu ----

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        textSize = 12f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun field(hint: String, inputType: Int, maxLen: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(0xFF5A6472.toInt())
        setTextColor(TEXT)
        textSize = 15f
        this.inputType = inputType
        setSingleLine(true)
        background = rounded(FIELD, 10, BORDER)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        if (maxLen > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = rounded(0xFF252C38.toInt(), 10)
        stateListAnimator = null
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL, 14)
        setPadding(dp(14), dp(4), dp(14), dp(14))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Başlık
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "TELSİZ"
            setTextColor(TEXT)
            textSize = 22f
            letterSpacing = 0.15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        txtNet = TextView(this).apply {
            text = "kapalı"
            setTextColor(MUTED)
            textSize = 13f
        }
        header.addView(txtNet)
        root.addView(header)

        // Kaydırılabilir orta bölüm
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
            isFillViewport = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val settings = panel()
        settings.addView(label("Adın"))
        edtNick = field("Adın", InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 16)
        settings.addView(edtNick)

        settings.addView(label("Kanal (1-999)"))
        edtChannel = field("1", InputType.TYPE_CLASS_NUMBER, 3)
        settings.addView(edtChannel)

        settings.addView(label("Relay sunucusu — boşsa sadece WiFi/hotspot"))
        edtRelay = field("https://siteniz.com/telsiz/relay.php", InputType.TYPE_TEXT_VARIATION_URI, 0)
        settings.addView(edtRelay)
        content.addView(settings)

        btnPower = Button(this).apply {
            text = "BAŞLAT"
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            background = rounded(0xFF252C38.toInt(), 10)
            stateListAnimator = null
            setOnClickListener {
                if (TelsizService.isRunning) stopSession() else requestAndStart()
            }
        }
        content.addView(btnPower, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(14) })

        // Ağ paneli: internet yokken ne yapılacağı buradan yönetiliyor.
        val netPanel = panel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        netPanel.addView(TextView(this).apply {
            text = "AĞ"
            setTextColor(MUTED)
            textSize = 11f
            letterSpacing = 0.15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        txtDirect = TextView(this).apply {
            text = "-"
            setTextColor(TEXT)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(10))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        netPanel.addView(txtDirect)

        btnDirect = smallButton("TELSİZ AĞI KUR") { toggleDirect() }
        netPanel.addView(btnDirect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))

        btnWifiSettings = smallButton("WiFi AYARLARINI AÇ") {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }
        netPanel.addView(btnWifiSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(8) })

        content.addView(netPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        val statusPanel = panel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        txtStatus = TextView(this).apply {
            text = "Kapalı"
            setTextColor(MUTED)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        statusPanel.addView(txtStatus)
        txtPeers = TextView(this).apply {
            text = "Kanalda kimse yok"
            setTextColor(TEXT)
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        statusPanel.addView(txtPeers)
        content.addView(statusPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14); bottomMargin = dp(16) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Konuşan göstergesi
        txtTalking = TextView(this).apply {
            text = ""
            setTextColor(ACCENT)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(txtTalking, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Bas-konuş
        val pttWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        }
        btnPtt = Button(this).apply {
            text = "BAS\nKONUŞ"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = pttBackground()
            stateListAnimator = null
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!TelsizService.isRunning) {
                            toast("Önce BAŞLAT'a bas")
                        } else {
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            service?.startTx()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        service?.stopTx()
                        v.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        pttWrap.addView(btnPtt, LinearLayout.LayoutParams(dp(180), dp(180)))
        root.addView(pttWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        return root
    }

    private fun pttBackground(): StateListDrawable {
        fun circle(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), circle(ACCENT))
            addState(intArrayOf(), circle(PTT))
        }
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
        // Android 10+ Wi-Fi Direct'i konum iznine bağlamış durumda.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        svc.createDirectGroup()
    }

    private fun startSession() {
        prefs.nick = edtNick.text.toString().trim()
        prefs.channel = (edtChannel.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
        prefs.relayUrl = edtRelay.text.toString().trim()
        edtChannel.setText(prefs.channel.toString())

        startForegroundService(Intent(this, TelsizService::class.java))
        bind()
        refresh()
    }

    private fun stopSession() {
        service?.stopTx()
        unbind()
        stopService(Intent(this, TelsizService::class.java))
        refresh()
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

    // ---- durum tazeleme ----

    /**
     * Ağ paneli. Kullanıcının asıl sorusu "internet yokken ne olacak" —
     * cevabı burada, duruma göre tek bir sonraki adım olarak veriyoruz.
     */
    private fun refreshNetworkPanel(svc: TelsizService) {
        btnDirect.alpha = 1f
        val ssid = svc.directSsid

        if (ssid != null) {
            btnDirect.text = "AĞI KAPAT"
            txtDirect.text = buildString {
                append("Telsiz ağı yayında — internet gerekmiyor.\n\n")
                append("Ağ adı : ").append(ssid).append('\n')
                append("Parola : ").append(svc.directPassphrase ?: "-").append('\n')
                append("Bağlı  : ").append(svc.directClients).append(" cihaz\n\n")
                append("Diğerleri WiFi ayarlarından bu ağa bağlansın.\n")
                append("Menzil ~50-100 m; açık alanda ve telefon yüksekteyken daha iyi.")
                append(bridgeText(svc))
            }
            return
        }

        btnDirect.text = "TELSİZ AĞI KUR"
        val lanOk = svc.lanError == null && svc.lanIp != "-"
        txtDirect.text = when {
            lanOk -> "Yerel ağdasın (" + svc.lanIp + ").\n" +
                "Aynı ağdakilerle internet olmadan konuşabilirsin." +
                bridgeText(svc) +
                "\n\nAğ yoksa \"TELSİZ AĞI KUR\" ile kendi ağını yayınla."
            !svc.directSupported() ->
                "Ağ yok. Bu cihaz Wi-Fi Direct desteklemiyor —\n" +
                    "biri telefonundan hotspot açsın, diğerleri bağlansın."
            svc.directState != "kapalı" && svc.directState != "ağ açık" ->
                "Ağ yok.\n" + svc.directState + "\n\nWiFi'nin açık olduğundan emin ol."
            else ->
                "Ağ yok — ne WiFi ne hotspot.\n\n" +
                    "\"TELSİZ AĞI KUR\"a bas: telefonun kendi kablosuz ağını\n" +
                    "yayınlar, diğerleri ona bağlanır. İnternet gerekmez.\n" +
                    "(WiFi açık olmalı, bağlı olması gerekmiyor.)"
        }
    }

    /**
     * Köprü, menzili donanım olmadan uzatmanın tek gerçek yolu: şebekesi olan
     * biri yerel grubu internete bağlıyor. Gerçekten aktığını görebilmek için
     * aktarılan paket sayısını da yazıyoruz.
     */
    private fun bridgeText(svc: TelsizService): String =
        if (!svc.bridging) ""
        else "\n\n● Köprü açık — bu telefon grubu internete bağlıyor.\n" +
            "   " + svc.bridged + " paket aktarıldı."

    private fun refresh() {
        val running = TelsizService.isRunning
        val svc = service

        btnPower.text = if (running) "DURDUR" else "BAŞLAT"
        btnPtt.isEnabled = running
        btnPtt.alpha = if (running) 1f else 0.4f
        for (v in arrayOf<View>(edtNick, edtChannel, edtRelay)) {
            v.isEnabled = !running
            v.alpha = if (running) 0.5f else 1f
        }

        if (!running) {
            txtNet.text = "kapalı"
            txtStatus.text = svc?.startError ?: "Kapalı"
            txtPeers.text = "Kanalda kimse yok"
            txtTalking.text = ""
            txtDirect.text = "Telsiz kapalı"
            btnDirect.text = "TELSİZ AĞI KUR"
            btnDirect.alpha = 0.4f
            return
        }
        if (svc == null) {
            txtNet.text = "başlıyor"
            bind()
            return
        }

        refreshNetworkPanel(svc)

        val lanOk = svc.lanError == null
        txtNet.text = buildString {
            append("K").append(svc.currentChannel())
            append(if (lanOk) " · LAN✓" else " · LAN✗")
            if (svc.relayEnabled) append(if (svc.relayConnected) " · NET✓" else " · NET…")
        }

        txtStatus.text = buildString {
            append("Ad    : ").append(svc.currentNick()).append('\n')
            append("Kanal : ").append(svc.currentChannel()).append('\n')
            append("WiFi  : ").append(if (lanOk) svc.lanIp else (svc.lanError ?: "hata")).append('\n')
            append("Relay : ").append(
                when {
                    !svc.relayEnabled -> "yok"
                    svc.relayConnected -> "bağlı"
                    else -> svc.relayStatus
                }
            )
        }

        val peers = svc.peerList()
        txtPeers.text = if (peers.isEmpty()) "Kanalda kimse yok"
        else "Kanalda " + peers.size + " kişi:\n" + peers.joinToString("\n") { "• " + it.nick }

        val talking = svc.talkingNow()
        txtTalking.text = when {
            svc.transmitting -> "● GÖNDERİYORSUN"
            talking.isNotEmpty() -> "🔊 " + talking.joinToString(", ") + " konuşuyor"
            else -> ""
        }
    }
}
