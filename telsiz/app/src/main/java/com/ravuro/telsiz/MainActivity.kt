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
 * Ekran iki durumlu. Kapalıyken ayarlar görünür; açıldığında ayarlar gizlenip
 * yerine bağlantı durumu ve kanaldakiler gelir. Konuşurken kimsenin ad/kanal
 * alanına bakmaya ihtiyacı yok — o alanlar zaten kilitliydi, yer kaplıyordu.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_MIC = 1
        const val REQ_LOCATION = 2

        const val BG = 0xFF0B0E14.toInt()
        const val SURFACE = 0xFF141922.toInt()
        const val SURFACE2 = 0xFF1B2230.toInt()
        const val BORDER = 0xFF232B39.toInt()
        const val TEXT = 0xFFEAEEF5.toInt()
        const val MUTED = 0xFF7C8798.toInt()
        const val GREEN = 0xFF3DDC84.toInt()
        const val BLUE = 0xFF3B82F6.toInt()
        const val RED = 0xFFF87171.toInt()
        const val AMBER = 0xFFFBBF24.toInt()
    }

    private lateinit var prefs: Prefs

    private lateinit var edtNick: EditText
    private lateinit var edtChannel: EditText
    private lateinit var edtRelay: EditText
    private lateinit var btnVolumePtt: Button
    private lateinit var setupCard: View
    private lateinit var liveCard: View
    private lateinit var netCard: View
    private lateinit var btnPower: Button
    private lateinit var ptt: PttButton
    private lateinit var chanBadge: TextView
    private lateinit var chipLan: TextView
    private lateinit var chipNet: TextView
    private lateinit var chipBridge: TextView
    private lateinit var txtPeers: TextView
    private lateinit var txtTalking: TextView
    private lateinit var txtDirect: TextView
    private lateinit var btnDirect: Button
    private lateinit var btnWifiSettings: Button
    private lateinit var txtHint: TextView

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
        window.navigationBarColor = BG

        prefs = Prefs(this)
        setContentView(buildUi())

        edtNick.setText(prefs.nick)
        edtChannel.setText(prefs.channel.toString())
        edtRelay.setText(prefs.relayUrl)
        updateVolumePttButton()
    }

    override fun onStart() {
        super.onStart()
        bind()
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        // Ekrandan çıkarken tuş basılı kalmış olabilir.
        service?.stopTx()
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
    // Ses yükseltme tuşu bas-konuş oluyor; ses kısma tuşu ses ayarı olarak
    // kalıyor ki karşı taraf kısık geldiğinde çaresiz kalınmasın. Yalnızca
    // uygulama ekrandayken çalışır: arka planda tuşları sistem alıyor.

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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 18, BORDER)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t
        setTextColor(MUTED)
        textSize = 11f
        letterSpacing = 0.16f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(MUTED)
        textSize = 12f
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun field(hint: String, type: Int, maxLen: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(0xFF4E5867.toInt())
        setTextColor(TEXT)
        textSize = 15f
        inputType = type
        setSingleLine(true)
        background = rounded(0xFF0D1119.toInt(), 12, BORDER)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        if (maxLen > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
    }

    private fun chip() = TextView(this).apply {
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(SURFACE2, 20)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        gravity = Gravity.CENTER
    }

    private fun filledButton(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        setTextColor(0xFF07130C.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        background = rounded(color, 14)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun softButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setTextColor(TEXT)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(SURFACE2, 12, BORDER)
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

        // --- başlık ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "TELSİZ"
            setTextColor(TEXT)
            textSize = 23f
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chanBadge = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            background = rounded(SURFACE2, 20, BORDER)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        header.addView(chanBadge)
        root.addView(header)

        // --- kaydırılabilir gövde ---
        val scroll = ScrollView(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Ayarlar (yalnızca kapalıyken)
        val setup = card()
        setup.addView(caption("AYARLAR"))
        setup.addView(label("Adın"))
        edtNick = field("Adın", InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 16)
        setup.addView(edtNick)
        setup.addView(label("Kanal (1-999)"))
        edtChannel = field("1", InputType.TYPE_CLASS_NUMBER, 3)
        setup.addView(edtChannel)
        setup.addView(label("Relay sunucusu — boşsa yalnızca yerel ağ"))
        edtRelay = field("https://…/relay.php", InputType.TYPE_TEXT_VARIATION_URI, 0)
        setup.addView(edtRelay)

        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        volRow.addView(TextView(this).apply {
            text = "Ses yükseltme tuşuyla konuş"
            setTextColor(TEXT)
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnVolumePtt = softButton("AÇIK") {
            prefs.volumePtt = !prefs.volumePtt
            updateVolumePttButton()
        }
        volRow.addView(btnVolumePtt, LinearLayout.LayoutParams(dp(88), dp(38)))
        setup.addView(volRow)
        setupCard = setup
        content.addView(setup)

        // Bağlantı durumu (yalnızca açıkken)
        val live = card()
        live.addView(caption("BAĞLANTI"))
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        chipLan = chip(); chipNet = chip(); chipBridge = chip()
        val gap = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        chips.addView(chipLan, gap)
        chips.addView(chipNet, LinearLayout.LayoutParams(gap))
        chips.addView(chipBridge)
        live.addView(chips)

        txtPeers = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        live.addView(txtPeers)
        liveCard = live
        content.addView(live, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // Ağ
        val net = card()
        net.addView(caption("AĞ"))
        txtDirect = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(14))
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        net.addView(txtDirect)
        btnDirect = softButton("TELSİZ AĞI KUR") { toggleDirect() }
        net.addView(btnDirect, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
        btnWifiSettings = softButton("WiFi AYARLARINI AÇ") {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }
        net.addView(btnWifiSettings, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) })
        netCard = net
        content.addView(net, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        btnPower = filledButton("BAŞLAT", GREEN) {
            if (TelsizService.isRunning) stopSession() else requestAndStart()
        }
        content.addView(btnPower, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(14); bottomMargin = dp(18) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // --- konuşan göstergesi ---
        txtTalking = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(txtTalking)

        // --- bas-konuş ---
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
        pttWrap.addView(ptt, LinearLayout.LayoutParams(dp(196), dp(196)))
        root.addView(pttWrap)

        txtHint = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(24), dp(8), dp(24), dp(22))
        }
        root.addView(txtHint)

        return root
    }

    private fun updateVolumePttButton() {
        val on = prefs.volumePtt
        btnVolumePtt.text = if (on) "AÇIK" else "KAPALI"
        btnVolumePtt.setTextColor(if (on) GREEN else MUTED)
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
        liveCard.visibility = if (running) View.VISIBLE else View.GONE
        netCard.visibility = if (running) View.VISIBLE else View.GONE
        chanBadge.visibility = if (running) View.VISIBLE else View.GONE

        ptt.live = running
        ptt.transmitting = svc?.transmitting == true
        ptt.receiving = running && svc != null && svc.talkingNow().isNotEmpty()

        if (!running) {
            btnPower.text = "BAŞLAT"
            btnPower.background = rounded(GREEN, 14)
            btnPower.setTextColor(0xFF07130C.toInt())
            txtTalking.text = ""
            txtHint.text = svc?.startError ?: "Başlatmak için mikrofon izni gerekiyor."
            return
        }

        btnPower.text = "DURDUR"
        btnPower.background = rounded(SURFACE2, 14, BORDER)
        btnPower.setTextColor(RED)

        if (svc == null) {
            txtHint.text = "Başlatılıyor…"
            bind()
            return
        }

        chanBadge.text = "KANAL " + svc.currentChannel()

        val lanOk = svc.lanError == null && svc.lanIp != "-"
        setChip(chipLan, if (lanOk) "● Yerel ağ" else "● Ağ yok", if (lanOk) GREEN else RED)
        when {
            !svc.relayEnabled -> setChip(chipNet, "● İnternet yok", MUTED)
            svc.relayConnected -> setChip(chipNet, "● İnternet", GREEN)
            else -> setChip(chipNet, "● Bağlanıyor", AMBER)
        }
        chipBridge.visibility = if (svc.bridging) View.VISIBLE else View.GONE
        if (svc.bridging) setChip(chipBridge, "● Köprü", BLUE)

        val peers = svc.peerList()
        txtPeers.text = if (peers.isEmpty()) {
            "Kanalda başka kimse yok."
        } else {
            "Kanalda " + peers.size + " kişi\n" + peers.joinToString("\n") { "•  " + it.nick }
        }

        refreshNetworkCard(svc)

        val talking = svc.talkingNow()
        txtTalking.text = when {
            svc.transmitting -> "GÖNDERİYORSUN"
            talking.isNotEmpty() -> talking.joinToString(", ") + " konuşuyor"
            else -> ""
        }
        txtTalking.setTextColor(if (svc.transmitting) GREEN else 0xFF60C8E8.toInt())

        txtHint.text = if (prefs.volumePtt) {
            "Düğmeyi ya da ses yükseltme tuşunu basılı tut"
        } else {
            "Konuşmak için düğmeyi basılı tut"
        }
    }

    private fun setChip(v: TextView, text: String, color: Int) {
        v.text = text
        v.setTextColor(color)
    }

    /**
     * Ağ kartı. Kullanıcının asıl sorusu "internet yokken ne olacak" —
     * cevabı burada, duruma göre tek bir sonraki adım olarak veriyoruz.
     */
    private fun refreshNetworkCard(svc: TelsizService) {
        val ssid = svc.directSsid
        if (ssid != null) {
            btnDirect.text = "AĞI KAPAT"
            txtDirect.text = buildString {
                append("Telsiz ağı yayında — internet gerekmiyor.\n\n")
                append("Ağ adı  ").append(ssid).append('\n')
                append("Parola  ").append(svc.directPassphrase ?: "-").append('\n')
                append("Bağlı   ").append(svc.directClients).append(" cihaz\n\n")
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
                    "\"TELSİZ AĞI KUR\"a bas: telefonun kendi kablosuz ağını yayınlar, " +
                    "diğerleri ona bağlanır. İnternet gerekmez.\n" +
                    "(WiFi açık olmalı, bağlı olması gerekmiyor.)"
        }
    }

    private fun bridgeText(svc: TelsizService): String =
        if (!svc.bridging) ""
        else "\n\nKöprü açık — bu telefon grubu internete bağlıyor.\n" +
            svc.bridged + " paket aktarıldı."
}
