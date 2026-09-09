package com.ravuro.telsiz

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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

    private var service: TelsizService? = null
    private var bindRequested = false
    private val ui = Handler(Looper.getMainLooper())

    /** İzin verilir verilmez başlatmak için: kullanıcı iki kez basmasın. */
    private var startAfterPermission = false

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

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] != false
        if (!micOk) {
            startAfterPermission = false
            Toast.makeText(this, "Mikrofon izni olmadan telsiz çalışmaz", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (startAfterPermission) {
            startAfterPermission = false
            startSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)

        edtNick = findViewById(R.id.edtNick)
        edtChannel = findViewById(R.id.edtChannel)
        edtRelay = findViewById(R.id.edtRelay)
        btnPower = findViewById(R.id.btnPower)
        btnPtt = findViewById(R.id.btnPtt)
        txtNet = findViewById(R.id.txtNet)
        txtStatus = findViewById(R.id.txtStatus)
        txtPeers = findViewById(R.id.txtPeers)
        txtTalking = findViewById(R.id.txtTalking)

        edtNick.setText(prefs.nick)
        edtChannel.setText(prefs.channel.toString())
        edtRelay.setText(prefs.relayUrl)

        btnPower.setOnClickListener {
            if (TelsizService.isRunning) stopSession() else requestAndStart()
        }

        btnPtt.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!TelsizService.isRunning) {
                        Toast.makeText(this, "Önce BAŞLAT'a bas", Toast.LENGTH_SHORT).show()
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

    /** Servis ayakta değilse bağlanma isteği başarısız olur; bu normal. */
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

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 400)
        }
    }

    // ---- oturum ----

    private fun requestAndStart() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isEmpty()) {
            startSession()
        } else {
            startAfterPermission = true
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    private fun startSession() {
        prefs.nick = edtNick.text.toString().trim()
        prefs.channel = (edtChannel.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
        prefs.relayUrl = edtRelay.text.toString().trim()
        edtChannel.setText(prefs.channel.toString())

        ContextCompat.startForegroundService(this, Intent(this, TelsizService::class.java))
        bind()
        refresh()
    }

    private fun stopSession() {
        service?.stopTx()
        unbind()
        stopService(Intent(this, TelsizService::class.java))
        refresh()
    }

    // ---- arayüz tazeleme ----

    private fun refresh() {
        val running = TelsizService.isRunning
        val svc = service

        btnPower.text = if (running) "DURDUR" else "BAŞLAT"
        btnPtt.isEnabled = running
        btnPtt.alpha = if (running) 1f else 0.4f

        setFieldsEnabled(!running)

        if (!running) {
            txtNet.text = "kapalı"
            txtStatus.text = svc?.startError ?: "Kapalı"
            txtPeers.text = "Kanalda kimse yok"
            txtTalking.text = ""
            return
        }
        if (svc == null) {
            txtNet.text = "başlıyor"
            bind()
            return
        }

        val lanOk = svc.lanError == null
        val relayTxt = when {
            !svc.relayEnabled -> "yok"
            svc.relayConnected -> "bağlı"
            else -> svc.relayStatus
        }

        txtNet.text = buildString {
            append("K").append(svc.currentChannel())
            append(if (lanOk) " · LAN✓" else " · LAN✗")
            if (svc.relayEnabled) append(if (svc.relayConnected) " · NET✓" else " · NET…")
        }

        txtStatus.text = buildString {
            append("Ad     : ").append(svc.currentNick()).append('\n')
            append("Kanal  : ").append(svc.currentChannel()).append('\n')
            append("WiFi   : ").append(if (lanOk) svc.lanIp else (svc.lanError ?: "hata")).append('\n')
            append("Relay  : ").append(relayTxt)
        }

        val peers = svc.peerList()
        txtPeers.text = if (peers.isEmpty()) {
            "Kanalda kimse yok"
        } else {
            "Kanalda " + peers.size + " kişi:\n" + peers.joinToString("\n") { "• " + it.nick }
        }

        val talking = svc.talkingNow()
        txtTalking.text = when {
            svc.transmitting -> "● GÖNDERİYORSUN"
            talking.isNotEmpty() -> "🔊 " + talking.joinToString(", ") + " konuşuyor"
            else -> ""
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        for (v in arrayOf<View>(edtNick, edtChannel, edtRelay)) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.5f
        }
    }
}
