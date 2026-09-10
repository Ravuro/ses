package com.ravuro.telsiz

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kanal parolasıyla ses şifreleme.
 *
 * Anahtar paroladan ve kanal numarasından türetiliyor: aynı parola farklı
 * kanalda farklı anahtar veriyor. Tuz sabit olmak zorunda — cihazlar
 * arasında anahtar değişimi yok, herkes aynı anahtara paroladan varıyor.
 * Bunun bedeli: parola listesiyle önceden hesap yapılabilir, dolayısıyla
 * parola tahmin edilebilir olmamalı.
 *
 * Yük AES-256-GCM ile şifreleniyor. Başlık şifrelenmiyor (kanal ve hedef
 * bilgisi yönlendirme için gerekli) ama kimliği doğrulanıyor: başlıktaki
 * tek bir baytı değiştiren paket çözülemez, yani kimse paketin kanalını,
 * gönderenini ya da hedefini değiştiremez.
 *
 * Nonce = gönderen kimliği (8) + sıra numarası (4). GCM'de aynı anahtarla
 * aynı nonce'u iki kez kullanmak anahtarı ifşa eder, bu yüzden sıra
 * numarası uygulama yeniden başlayınca sıfırdan başlamıyor; [Prefs] her
 * açılışta bir öncekinin bittiği yerin ilerisinden devam ettiriyor.
 */
class ChannelCrypto private constructor(private val key: SecretKey) {

    companion object {
        const val TAG_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val ITERATIONS = 100_000
        private const val KEY_BITS = 256

        /** Parola boşsa şifreleme yok. Anahtar türetme yavaştır, bir kez çağır. */
        fun derive(passphrase: String, channel: Int): ChannelCrypto? {
            val p = passphrase.trim()
            if (p.isEmpty()) return null
            return try {
                val salt = "telsiz-kanal-$channel".toByteArray(Charsets.UTF_8)
                val spec = PBEKeySpec(p.toCharArray(), salt, ITERATIONS, KEY_BITS)
                val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).encoded
                ChannelCrypto(SecretKeySpec(raw, "AES"))
            } catch (_: Exception) {
                null
            }
        }
    }

    // Cipher iş parçacığı güvenli değil; ses ve yoklama ayrı thread'lerden geliyor.
    private val ciphers = ThreadLocal.withInitial {
        Cipher.getInstance("AES/GCM/NoPadding")
    }

    private fun nonce(senderId: Long, seq: Int): ByteArray {
        val n = ByteArray(NONCE_BYTES)
        for (i in 0 until 8) n[i] = ((senderId shr (56 - i * 8)) and 0xFF).toByte()
        n[8] = ((seq shr 24) and 0xFF).toByte()
        n[9] = ((seq shr 16) and 0xFF).toByte()
        n[10] = ((seq shr 8) and 0xFF).toByte()
        n[11] = (seq and 0xFF).toByte()
        return n
    }

    /**
     * [plain] içindeki [plainLen] baytı [out] dizisinin [outOff] konumundan
     * itibaren şifreler. Yazılan bayt sayısını döner (plainLen + [TAG_BYTES]).
     * [aad] başlığın kimliği doğrulanan kısmı.
     */
    fun seal(
        aad: ByteArray, aadLen: Int,
        plain: ByteArray, plainLen: Int,
        senderId: Long, seq: Int,
        out: ByteArray, outOff: Int
    ): Int {
        val c = ciphers.get()
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(senderId, seq)))
        c.updateAAD(aad, 0, aadLen)
        return c.doFinal(plain, 0, plainLen, out, outOff)
    }

    /**
     * Çözer; kimlik doğrulaması tutmazsa -1 döner. Bozuk ya da başka bir
     * paroladan gelen paketler sessizce düşer.
     */
    fun open(
        aad: ByteArray, aadLen: Int,
        cipherText: ByteArray, off: Int, len: Int,
        senderId: Long, seq: Int,
        out: ByteArray
    ): Int {
        if (len <= TAG_BYTES) return -1
        return try {
            val c = ciphers.get()
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(senderId, seq)))
            c.updateAAD(aad, 0, aadLen)
            c.doFinal(cipherText, off, len, out, 0)
        } catch (_: GeneralSecurityException) {
            -1
        } catch (_: Exception) {
            -1
        }
    }
}
