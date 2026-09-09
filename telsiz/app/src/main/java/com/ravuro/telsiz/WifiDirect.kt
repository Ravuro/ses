package com.ravuro.telsiz

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/**
 * Hiçbir altyapı olmadan ağ kurar.
 *
 * Wi-Fi Direct grubu, router'a da mobil şebekeye de ihtiyaç duymaz: telefon
 * kendi kablosuz ağını yayınlar ve diğerleri ona bağlanır. Grup kurulunca
 * sistem yeni bir ağ arayüzü (p2p-...) açar; LanTransport zaten tüm
 * arayüzleri tarayıp yayın yaptığı için ses oradan akmaya başlar — yani bu
 * sınıf ses yolunu hiç bilmiyor, sadece altında bir ağ oluşturuyor.
 *
 * Android 10 ve üstünde ağ adı ile parolayı biz belirleyebiliyoruz. Bu önemli:
 * herkes adı ve parolayı önceden bildiği için, kuran kişinin kimseye bir şey
 * söylemesine gerek kalmıyor — diğerleri doğrudan bağlanabiliyor.
 */
class WifiDirect(
    private val ctx: Context,
    private val onChange: () -> Unit
) {
    companion object {
        /** Wi-Fi Direct ağ adları "DIRECT-" ile başlamak zorunda. */
        const val NETWORK_NAME = "DIRECT-Telsiz"
        const val PASSPHRASE = "telsiz1234"
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile var state: String = "kapalı"
        private set
    @Volatile var ssid: String? = null
        private set
    @Volatile var passphrase: String? = null
        private set
    @Volatile var clientCount: Int = 0
        private set
    @Volatile var isOwner: Boolean = false
        private set

    val active: Boolean get() = ssid != null

    /** Cihaz Wi-Fi Direct destekliyor mu? */
    fun supported(): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---- grup kurma ----

    @SuppressLint("MissingPermission")
    fun createGroup() {
        if (!supported()) {
            setState("bu cihaz desteklemiyor")
            return
        }
        if (!hasPermission()) {
            setState("konum izni gerekiyor")
            return
        }
        if (!ensureChannel()) return

        setState("ağ kuruluyor")

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Ağ adı/parolası grup bilgisi geldiğinde dolacak.
                requestGroupInfo()
            }

            override fun onFailure(reason: Int) {
                setState(
                    when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "cihaz desteklemiyor"
                        WifiP2pManager.BUSY -> "meşgul, tekrar dene"
                        WifiP2pManager.ERROR -> "kurulamadı — WiFi açık mı?"
                        else -> "kurulamadı ($reason)"
                    }
                )
            }
        }

        val m = manager ?: return
        val c = channel ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Sabit ad ve parola: herkes önceden bildiği için bağlanmak
                // adı sormaya gerek kalmadan mümkün oluyor.
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(NETWORK_NAME)
                    .setPassphrase(PASSPHRASE)
                    .enablePersistentMode(false)
                    // 2.4 GHz'de kal. 5 GHz daha hızlı ama menzili belirgin
                    // ölçüde kısa ve duvarı çok daha kötü geçiyor; telsizde
                    // hız değil mesafe önemli.
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
                m.createGroup(c, cfg, listener)
            } else {
                // Android 9 ve altında ad/parola sistem tarafından üretiliyor;
                // arayüzde gösterip kullanıcının aktarmasını istiyoruz.
                @Suppress("DEPRECATION")
                m.createGroup(c, listener)
            }
        } catch (e: Exception) {
            setState("kurulamadı: " + (e.message ?: "bilinmeyen hata"))
        }
    }

    fun removeGroup() {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = clearGroup("kapalı")
                override fun onFailure(reason: Int) = clearGroup("kapalı")
            })
        } catch (_: Exception) {
            clearGroup("kapalı")
        }
    }

    fun stop() {
        unregister()
        removeGroup()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) channel?.close()
        } catch (_: Exception) {
        }
        channel = null
        manager = null
        clearGroup("kapalı")
    }

    // ---- iç işler ----

    private fun ensureChannel(): Boolean {
        if (manager != null && channel != null) return true
        return try {
            val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val c = m.initialize(ctx, Looper.getMainLooper(), null)
            if (c == null) {
                setState("Wi-Fi Direct açılamadı")
                return false
            }
            manager = m
            channel = c
            register()
            true
        } catch (e: Exception) {
            setState("Wi-Fi Direct yok: " + (e.message ?: ""))
            false
        }
    }

    private fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    requestGroupInfo()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        try {
            ctx.registerReceiver(r, filter)
            receiver = r
        } catch (_: Exception) {
        }
    }

    private fun unregister() {
        val r = receiver ?: return
        receiver = null
        try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val m = manager ?: return
        val c = channel ?: return
        if (!hasPermission()) return
        try {
            m.requestGroupInfo(c) { group: WifiP2pGroup? ->
                if (group == null) {
                    clearGroup(if (state == "ağ kuruluyor") state else "kapalı")
                } else {
                    ssid = group.networkName
                    passphrase = group.passphrase
                    isOwner = group.isGroupOwner
                    clientCount = group.clientList?.size ?: 0
                    setState("ağ açık")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun clearGroup(newState: String) {
        ssid = null
        passphrase = null
        clientCount = 0
        isOwner = false
        setState(newState)
    }

    private fun setState(s: String) {
        state = s
        onChange()
    }
}
