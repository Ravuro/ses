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
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import android.widget.FrameLayout
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
    private lateinit var edtPass: EditText
    private lateinit var edtInvite: EditText
    private lateinit var txtInviteState: TextView
    private lateinit var btnShare: Button
    private lateinit var manualCard: View
    private lateinit var manualLink: TextView
    private lateinit var txtLock: TextView
    private lateinit var volumeToggle: Toggle
    private lateinit var beepToggle: Toggle
    private lateinit var exclusiveToggle: Toggle

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
    private lateinit var keyHelpCard: View
    private lateinit var txtKeyHelp: TextView
    private lateinit var btnKeyHelp: Button
    private lateinit var powerCard: View
    private lateinit var txtPowerWarn: TextView
    private lateinit var replayBtn: Button
    private lateinit var inviteBtn: Button
    private lateinit var warnCard: View
    private lateinit var txtWarn: TextView
    private lateinit var volumeCard: View
    private lateinit var txtVolume: TextView
    private lateinit var peersCard: View
    private lateinit var peersCount: TextView
    private lateinit var peersList: LinearLayout
    private lateinit var netCard: View
    private lateinit var diagCard: View
    private lateinit var txtDiag: TextView
    private lateinit var txtDirect: TextView
    private lateinit var btnDirect: Button
    private lateinit var btnWifiSettings: Button

    private lateinit var statePill: View
    private lateinit var stateDot: View
    private lateinit var stateLabel: TextView
    private lateinit var btnPower: Button
    private lateinit var ptt: PttButton
    private lateinit var picker: TargetPicker
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
        CrashLog.install(this)
        setContentView(buildUi())

        edtNick.setText(prefs.nick)
        edtChannel.setText(prefs.channel.toString())
        edtPass.setText(prefs.passphrase)
        edtInvite.setText(prefs.invite)
        // Kodla gelen kanalın numarası da parolası da koddan türüyor: elle
        // ayar kartını göstermeye gerek yok, karışıklık olur.
        setManualVisible(prefs.invite.isEmpty())
        refreshInvite()
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

        content.addView(buildPowerCard())
        content.addView(buildLiveHero())
        content.addView(buildTelemetry(), marginTop(0))
        content.addView(buildWarnCard(), marginTop(14))
        content.addView(buildPeers(), marginTop(14))

        replayBtn = outlineButton("SON KONUŞMAYI ÇAL", TEXT2, LINE2) {
            if (service?.replayLast() != true) toast("Tekrar çalacak bir şey yok")
        }
        content.addView(replayBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(10) })

        // Kanalı kurduktan sonra da davet edebilmek gerek: kod burada duruyor.
        inviteBtn = outlineButton("", TEXT2, LINE2) { shareInvite(prefs.invite) }
        content.addView(inviteBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(9) })
        content.addView(buildSetup())
        content.addView(buildVolumeCard(), marginTop(12))
        content.addView(buildNetCard(), marginTop(12))
        content.addView(buildDiagCard(), marginTop(12))

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
                            // Seçilecek kimse yoksa bekletmenin anlamı yok:
                            // hedef zaten herkes. Boşuna bekletmek konuşmanın
                            // ilk 300 ms'sini geciktiriyordu.
                            if (openPicker()) {
                                service?.startTx(pendingTarget = true)
                                armSettle()
                            } else {
                                service?.startTx()
                            }
                            refresh()
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (picker.visibility == View.VISIBLE) {
                            val (x, y) = toPicker(ev)
                            val before = picker.selectedId
                            picker.moveFinger(x, y)
                            if (picker.selectedId != before) {
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            armSettle()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(settle)
                        service?.resolveTarget(picker.selectedId)
                        picker.close()
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

        // INVISIBLE: GONE olan görünüm hiç ölçülmüyor, ilk açılışta
        // genişliği sıfır oluyor ve yerleştirme hesabı patlıyordu.
        picker = TargetPicker(this).apply { visibility = View.INVISIBLE }
        val stack = FrameLayout(this)
        stack.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stack.addView(picker, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return stack
    }

    // ---- halka seçici ----
    //
    // Parmak bas-konuş düğmesinde başlayıp dışına çıktığı için koordinatlar
    // ekran üzerinden katmana çevriliyor.

    private val loc = IntArray(2)

    private fun toPicker(ev: MotionEvent): Pair<Float, Float> {
        picker.getLocationOnScreen(loc)
        return (ev.rawX - loc[0]) to (ev.rawY - loc[1])
    }

    /** Seçilecek kimse varsa halkayı açar; açtıysa true döner. */
    private fun openPicker(): Boolean {
        val svc = service ?: return false
        val items = svc.peerList().map { TargetPicker.Item(it.id, it.nick) }
        if (items.isEmpty()) return false
        picker.getLocationOnScreen(loc)
        val px = loc[0]
        val py = loc[1]
        ptt.getLocationOnScreen(loc)
        picker.open(
            items,
            (loc[0] - px + ptt.width / 2).toFloat(),
            (loc[1] - py + ptt.height / 2).toFloat()
        )
        return true
    }

    /**
     * Parmak durunca hedefi kesinleştir. Böylece hem yerinde tutan kişi
     * gecikmeden herkese konuşuyor, hem de sürükleyen kişi seçimini
     * bitirene kadar ses bekletiliyor.
     */
    private val settle = Runnable {
        service?.resolveTarget(picker.selectedId)
        refresh()
    }

    private fun armSettle() {
        ui.removeCallbacks(settle)
        ui.postDelayed(settle, 300)
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
        val capRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        capRow.addView(caption("KANAL"))
        txtLock = TextView(this).apply {
            textSize = 9f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.14f
            setPadding(dp(8), 0, 0, 0)
        }
        capRow.addView(txtLock)
        side.addView(capRow)
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

    /**
     * Pil kısıtlaması uyarısı. Xiaomi/Poco, Oppo, Vivo gibi arayüzler arka
     * plandaki uygulamayı donduruyor; donunca ne ses tuşu duyuluyor ne de
     * konuşma gidiyor. Kullanıcının bunu kendi bulması mümkün değil, o yüzden
     * uygulama söylüyor.
     */
    /**
     * Ses tuşu arka planda çalışmıyorsa erişilebilirlik yolu.
     *
     * Bu izin açılır pencereyle istenemiyor; sistem yalnızca kullanıcının
     * Ayarlar'dan elle açmasına izin veriyor. Uygulamanın yapabileceği tek
     * şey durumu okuyup doğru ekrana götürmek.
     */
    private fun buildKeyHelpCard(): View {
        val c = card()
        c.addView(caption("SES TUŞU ARKA PLANDA"))
        txtKeyHelp = body("", 13f, TEXT2).apply { setPadding(0, dp(10), 0, dp(14)) }
        c.addView(txtKeyHelp)
        btnKeyHelp = outlineButton("ERİŞİLEBİLİRLİK AYARLARINI AÇ", TEXT2, LINE2) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Listeden \"Telsiz ses tuşu\"nu aç")
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }
        c.addView(btnKeyHelp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
        keyHelpCard = c
        return c
    }

    /** Erişilebilirlik servisi kullanıcı tarafından açılmış mı? */
    private fun keyServiceEnabled(): Boolean {
        return try {
            val want = KeyService.componentName(packageName)
            val on = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            on.split(':').any { it.equals(want, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildPowerCard(): View {
        val c = card().apply {
            background = rounded(SURFACE, 18, 0xFF4A3A1C.toInt())
            setPadding(dp(16), dp(15), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(dot(6, AMBER))
        head.addView(TextView(this).apply {
            text = "PİL KISITLAMASI AÇIK"
            setTextColor(AMBER)
            textSize = 10f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.14f
            setPadding(dp(8), 0, 0, 0)
        })
        c.addView(head)

        txtPowerWarn = body("", 13f, TEXT2).apply { setPadding(0, dp(10), 0, dp(14)) }
        c.addView(txtPowerWarn)

        c.addView(outlineButton("PİL KISITLAMASINI KALDIR", AMBER, 0xFF4A3A1C.toInt()) {
            openBatterySettings()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        c.addView(outlineButton("UYGULAMA AYARLARINI AÇ", TEXT2, LINE2) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) })

        powerCard = c
        return c
    }

    private fun batteryRestricted(): Boolean {
        return try {
            val pm = getSystemService(PowerManager::class.java)
            pm != null && !pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** Üreticiye özel ek adım gerekiyor mu? */
    private fun aggressiveVendor(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return listOf("xiaomi", "poco", "redmi", "oppo", "realme", "vivo", "huawei", "honor")
            .any { m.contains(it) }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Ayarlar açılamadı")
            }
        }
    }

    private fun buildWarnCard(): View {
        val c = card().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFF1E1710.toInt(), 18, 0xFF4A3A1C.toInt())
        }
        c.addView(caption("NEDEN DUYMUYORSUN").apply { setTextColor(AMBER) })
        txtWarn = body("", 13f, 0xFFE8C98A.toInt()).apply { setPadding(0, dp(8), 0, 0) }
        c.addView(txtWarn)
        warnCard = c
        return c
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

    /**
     * Davet kodu kartı. Kurulumun ilk ve çoğu zaman tek adımı: kodu olan
     * yazıyor, olmayan bir kanal oluşturup kodu paylaşıyor.
     */
    private fun buildInviteCard(): View {
        val c = card().apply { setPadding(dp(20), dp(18), dp(20), dp(20)) }
        c.addView(caption("DAVET KODU"))

        edtInvite = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            setHintTextColor(FAINT)
            setTextColor(TEXT)
            textSize = 21f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            background = rounded(FIELD, 14, LINE2)
            setPadding(dp(10), dp(14), dp(10), dp(14))
            // Ayraçlarla birlikte 19 karakter.
            filters = arrayOf(android.text.InputFilter.LengthFilter(Invite.LENGTH + 3))
        }
        c.addView(edtInvite, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        txtInviteState = body("", 12f, DIM).apply { setPadding(0, dp(10), 0, 0) }
        c.addView(txtInviteState)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val btnNew = outlineButton("YENİ KANAL", GREEN, 0xFF1E4A36.toInt()) { newChannel() }
        row.addView(btnNew, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnShare = outlineButton("PAYLAŞ", TEXT2, LINE2) {
            val inv = Invite.parse(edtInvite.text.toString())
            if (inv == null) toast("Önce geçerli bir kod gerekiyor") else shareInvite(inv.code)
        }
        row.addView(btnShare, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(9)
        })
        c.addView(row)

        // Dinleyici en sonda bağlanıyor: [refreshInvite] durum satırına ve
        // paylaş düğmesine dokunuyor, ikisi de yukarıda kuruluyor. Daha
        // erken bağlanırsa alana yazılan ilk harf henüz var olmayan bir
        // görünüme çarpar.
        edtInvite.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}

            /**
             * Yazıldıkça ayraçları biz koyuyoruz; kullanıcı harfleri
             * ard arda yazsa da, yapıştırsa da aynı biçim çıkıyor.
             */
            override fun afterTextChanged(e: Editable) {
                if (formattingInvite) return
                formattingInvite = true
                val pretty = Invite.format(
                    Invite.normalize(e.toString()).take(Invite.LENGTH)
                )
                if (pretty != e.toString()) {
                    e.replace(0, e.length, pretty)
                    edtInvite.setSelection(edtInvite.text.length)
                }
                formattingInvite = false
                refreshInvite()
            }
        })

        c.addView(body(
            "Kanala yalnızca kodu bilen girebilir; kodu bilmeyen numarayı " +
                "denese de sesleri çözemez. Kod hem kanalı hem parolayı " +
                "taşıyor, ayrıca parola söylemene gerek yok.",
            12f, DIM
        ).apply { setPadding(0, dp(14), 0, 0) })

        return c
    }

    private var formattingInvite = false

    /** Kod alanının altındaki durum satırı: kodun tuttuğunu girmeden gör. */
    private fun refreshInvite() {
        val text = edtInvite.text.toString()
        val inv = Invite.parse(text)
        when {
            inv != null -> {
                txtInviteState.text = "Kanal ${inv.channel} · uçtan uca şifreli"
                txtInviteState.setTextColor(GREEN)
            }
            Invite.normalize(text).isEmpty() -> {
                txtInviteState.text =
                    "Kodu olan buraya yazsın, olmayan yeni kanal oluştursun."
                txtInviteState.setTextColor(DIM)
            }
            else -> {
                txtInviteState.text = "Kod eksik ya da yanlış yazılmış."
                txtInviteState.setTextColor(AMBER)
            }
        }
        val ok = inv != null
        btnShare.isEnabled = ok
        btnShare.alpha = if (ok) 1f else 0.45f
    }

    private fun newChannel() {
        edtInvite.setText(Invite.create().code)
        setManualVisible(false)
        toast("Yeni kanal hazır — kodu konuşacağın kişilere gönder")
    }

    private fun shareInvite(code: String) {
        try {
            val cb = getSystemService(android.content.ClipboardManager::class.java)
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("Telsiz daveti", code))
        } catch (_: Exception) {}
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Telsizde buluşalım. Davet kodu: " + code
                )
            }, "Davet kodunu gönder"))
        } catch (_: Exception) {
            toast("Kod panoya kopyalandı")
        }
    }

    /** Elle kanal seçme, davet kodunu kullanmayanlar için açılıp kapanıyor. */
    private fun setManualVisible(visible: Boolean) {
        manualCard.visibility = if (visible) View.VISIBLE else View.GONE
        manualLink.text = if (visible) "Davet koduna dön" else "Kanalı elle seç"
    }

    /**
     * Eski yol: numarayı ve parolayı ayrı ayrı gir. Kod olmadan buluşmak
     * isteyenler ve daha önce kurulmuş kanallar için duruyor.
     */
    private fun buildManualCard(): View {
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

        val passWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        passWrap.addView(caption("KANAL PAROLASI").apply { setPadding(0, 0, 0, dp(8)) })
        edtPass = field(
            "boşsa şifresiz",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            64
        )
        passWrap.addView(edtPass, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        passWrap.addView(body(
            "Parola koyarsan ses şifrelenir ve yalnızca aynı parolayı " +
                "girenler duyar. Parolayı kanaldakilere ayrıca söylemen " +
                "gerekiyor — uygulama onu hiçbir yere göndermiyor.",
            12f, DIM
        ).apply { setPadding(0, dp(8), 0, 0) })
        wrap.addView(passWrap)

        return wrap
    }

    private fun buildSetup(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        wrap.addView(buildInviteCard())

        manualLink = TextView(this).apply {
            text = "Kanalı elle seç"
            setTextColor(MUTED)
            textSize = 12f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { setManualVisible(manualCard.visibility != View.VISIBLE) }
        }
        wrap.addView(manualLink)

        manualCard = buildManualCard()
        wrap.addView(manualCard)

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

        wrap.addView(buildKeyHelpCard(), marginTop(14))

        volumeToggle = toggleRow(
            "Ses tuşuyla konuş",
            "Ses yükseltme tuşunu basılı tut —\nekran kapalıyken de",
            { prefs.volumePtt },
            { prefs.volumePtt = !prefs.volumePtt }
        )
        wrap.addView(volumeToggle.row, marginTop(14))

        exclusiveToggle = toggleRow(
            "Diğer uygulamaları sustur",
            "Kapalıyken müzik/video sadece biri\nkonuşurken kısılır, sonra devam eder",
            { prefs.exclusiveAudio },
            { prefs.exclusiveAudio = !prefs.exclusiveAudio }
        )
        wrap.addView(exclusiveToggle.row, marginTop(10))

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

    /**
     * Ses seviyesi.
     *
     * Telsiz açıkken ses tuşlarını medya oturumu alıyor: yükseltme tuşu
     * bas-konuş, kısma tuşu ses ayarı. Yani seviye düşürülebiliyor ama
     * yükseltilemiyordu — yanlışlıkla kısan kişinin telsizi sağır kalıyordu
     * ve çıkış yolu yoktu. Yükseltmenin bir yeri olmak zorunda; burası.
     */
    private fun buildVolumeCard(): View {
        val c = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(caption("SES SEVİYESİ"))
        txtVolume = TextView(this).apply {
            textSize = 13f
            typeface = Fonts.mono(context)
            setTextColor(TEXT)
            setPadding(0, dp(6), 0, 0)
        }
        texts.addView(txtVolume)
        c.addView(texts, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        c.addView(stepButton("−") { nudgeVolume(false) })
        c.addView(stepButton("+") { nudgeVolume(true) }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(9)
        })
        volumeCard = c
        return c
    }

    private fun nudgeVolume(up: Boolean) {
        try {
            getSystemService(AudioManager::class.java)?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
        } catch (_: Exception) {
        }
        refreshVolume()
    }

    private fun refreshVolume() {
        val (cur, max) = try {
            val am = getSystemService(AudioManager::class.java)
            (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) to
                (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0)
        } catch (_: Exception) {
            0 to 0
        }
        if (max <= 0) {
            txtVolume.text = "-"
            return
        }
        val filled = (cur * 12 + max / 2) / max
        val bar = "▮".repeat(filled) + "▯".repeat(12 - filled)
        txtVolume.text = bar + "  " + cur + "/" + max
        txtVolume.setTextColor(if (cur == 0) AMBER else TEXT)
    }

    /**
     * Tanı. "Sanırım çalışmıyor" ile "şu an röleden 96 saniyedir cevap yok"
     * arasındaki fark, sorunu bir denemede çözmekle üç denemede çözmek
     * arasındaki fark. Metin uzun basınca panoya kopyalanıyor.
     */
    private fun buildDiagCard(): View {
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(caption("TANI"), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        head.addView(TextView(this).apply {
            text = "KOPYALA"
            setTextColor(MUTED)
            textSize = 9f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.12f
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { copyDiag() }
        })
        head.addView(TextView(this).apply {
            text = "TEMİZLE"
            setTextColor(MUTED)
            textSize = 9f
            typeface = Fonts.mono(context, bold = true)
            letterSpacing = 0.12f
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener {
                prefs.lastCrash = ""
                toast("Çökme kaydı silindi")
                refresh()
            }
        })
        c.addView(head)

        txtDiag = TextView(this).apply {
            textSize = 11.5f
            typeface = Fonts.mono(context)
            setTextColor(TEXT2)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
            setOnLongClickListener { copyDiag(); true }
        }
        c.addView(txtDiag)
        diagCard = c
        return c
    }

    private fun copyDiag() {
        try {
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                android.content.ClipData.newPlainText("Telsiz tanı", txtDiag.text)
            )
            toast("Tanı panoya kopyalandı")
        } catch (_: Exception) {
            toast("Kopyalanamadı")
        }
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

        val typed = edtInvite.text.toString()
        val inv = Invite.parse(typed)
        if (inv != null) {
            // Kod varsa kanal da parola da ondan geliyor; elle girilen
            // alanlara bakılmıyor.
            prefs.invite = inv.code
            prefs.channel = inv.channel
            prefs.passphrase = inv.passphrase
        } else {
            if (Invite.normalize(typed).isNotEmpty()) {
                // Yarım kalmış kodla sessizce başka bir kanala düşmek,
                // "neden kimse yok" diye aranmaktan beter.
                toast("Davet kodu eksik ya da hatalı")
                setManualVisible(true)
                return
            }
            prefs.invite = ""
            prefs.channel = (edtChannel.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
            prefs.passphrase = edtPass.text.toString().trim()
            edtChannel.setText(prefs.channel.toString())
        }

        startForegroundService(Intent(this, TelsizService::class.java))
        bind()
        refresh()
    }

    private fun stopSession() {
        // Nabzın bunu "öldürülmüş" sanıp geri getirmemesi için önce niyeti
        // yazıyoruz; servis bunu okumadan durabiliyor.
        prefs.sessionWanted = false
        Watchdog.disarm(this)
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

        val restricted = batteryRestricted()
        powerCard.visibility = if (restricted) View.VISIBLE else View.GONE
        if (restricted) {
            txtPowerWarn.text = buildString {
                append("Bu telefon uygulamayı arka planda donduruyor. ")
                append("Donduğunda ses tuşuyla konuşma ve ekran kapalıyken ")
                append("dinleme durur.")
                if (aggressiveVendor()) {
                    append("\n\n")
                    append(Build.MANUFACTURER)
                    append(" telefonlarda ayrıca \"Otomatik başlatma\" iznini ")
                    append("açman ve uygulamayı son uygulamalar ekranında ")
                    append("kilitlemen gerekiyor.")
                }
            }
        }

        val keyOn = keyServiceEnabled()
        if (keyOn) {
            txtKeyHelp.text = "Açık. Uygulama arka plandayken ve kilit " +
                "ekranındayken ses tuşu çalışır.\n\nEkran tamamen " +
                "kapalıyken (karanlıkken) Android ses tuşlarını hiçbir " +
                "uygulamaya iletmiyor. Orada kulaklık düğmesi çalışır: bir " +
                "bas konuşmaya başla, bir daha bas bitir."
            btnKeyHelp.text = "AÇIK · KAPATMAK İÇİN DOKUN"
            btnKeyHelp.setTextColor(GREEN)
        } else {
            txtKeyHelp.text = "Bazı telefonlarda (Xiaomi, Poco, Oppo…) ses tuşu " +
                "arka planda çalışmaz. Bunu açarsan tuşlar sistemden doğrudan " +
                "alınır ve arka planda da kilit ekranında da çalışır.\n\n" +
                "Erişilebilirlik izni açılır pencereyle istenemiyor; listeden " +
                "\"Telsiz ses tuşu\"nu elle açman gerekiyor."
            btnKeyHelp.text = "ERİŞİLEBİLİRLİK AYARLARINI AÇ"
            btnKeyHelp.setTextColor(TEXT2)
        }

        setupCard.visibility = if (running) View.GONE else View.VISIBLE
        liveHero.visibility = if (running) View.VISIBLE else View.GONE
        telemetry.visibility = if (running) View.VISIBLE else View.GONE
        peersCard.visibility = if (running) View.VISIBLE else View.GONE
        val warn = if (running) svc?.mismatchWarning() else null
        warnCard.visibility = if (warn != null) View.VISIBLE else View.GONE
        if (warn != null) txtWarn.text = warn
        replayBtn.visibility = if (running) View.VISIBLE else View.GONE
        inviteBtn.visibility =
            if (running && prefs.invite.isNotEmpty()) View.VISIBLE else View.GONE
        if (inviteBtn.visibility == View.VISIBLE) {
            inviteBtn.text = "DAVET ET · " + prefs.invite
        }
        netCard.visibility = if (running) View.VISIBLE else View.GONE
        diagCard.visibility = if (running) View.VISIBLE else View.GONE
        volumeCard.visibility = if (running) View.VISIBLE else View.GONE
        if (running) refreshVolume()
        if (running) txtDiag.text = buildString {
            append(svc?.diagnostics() ?: "başlatılıyor…")
            val crash = prefs.lastCrash
            if (crash.isNotEmpty()) {
                append("\n\nSON ÇÖKME\n").append(crash)
            }
        }

        ptt.live = running
        ptt.transmitting = svc?.transmitting == true
        ptt.receiving = running && svc != null && svc.talkingIds().isNotEmpty()

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
        if (svc.encrypted) {
            txtLock.text = "ŞİFRELİ"
            txtLock.setTextColor(GREEN)
        } else {
            txtLock.text = "ŞİFRESİZ"
            txtLock.setTextColor(AMBER)
        }
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

        val secs = svc.replaySeconds
        replayBtn.isEnabled = svc.replayAvailable
        replayBtn.alpha = if (svc.replayAvailable) 1f else 0.45f
        replayBtn.text =
            if (svc.replayAvailable) "SON KONUŞMAYI ÇAL · $secs SN"
            else "TEKRAR DİNLENECEK KONUŞMA YOK"

        refreshPeers(svc)
        refreshNetCard(svc)

        val talking = svc.talkingNow()
        when {
            svc.transmitting -> {
                val t = svc.talkTarget
                val ad = if (t != 0L) svc.peerList().firstOrNull { it.id == t }?.nick else null
                txtBanner.text = if (ad != null) "YALNIZCA " + ad.uppercase() else "MİKROFON AÇIK"
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
            keyOn -> "Düğmeyi ya da ses yükseltme tuşunu basılı tut\n" +
                "Ekran karanlıkken kulaklık düğmesi"
            svc.keyPttReady -> {
                val n = svc.keyPttEvents
                "Düğmeyi ya da ses yükseltme tuşunu basılı tut\n" +
                    if (n > 0) "Ekran kapalıyken de çalışıyor · $n olay"
                    else "Ekran kapalıyken çalışmazsa ayarlardan erişilebilirliği aç"
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
        val talking = svc.talkingIds()
        val sig = peers.joinToString("|") {
            it.id.toString() + it.nick +
                (if (talking.contains(it.id)) "*" else "") +
                (if (svc.isMuted(it.id)) "#" else "")
        }
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
            val speaking = talking.contains(p.id)
            val isMuted = svc.isMuted(p.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                alpha = if (isMuted) 0.45f else 1f
                setOnClickListener {
                    val now = service?.toggleMute(p.id) == true
                    toast(if (now) p.nick + " sessize alındı" else p.nick + " tekrar duyulacak")
                    peersSignature = ""
                    refresh()
                }
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
                text = when {
                    isMuted -> "SESSİZDE"
                    speaking -> "KONUŞUYOR"
                    else -> "dinliyor"
                }
                setTextColor(when {
                    isMuted -> AMBER
                    speaking -> CYAN
                    else -> DIM
                })
                textSize = 10f
                typeface = Fonts.mono(context, bold = speaking || isMuted)
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
