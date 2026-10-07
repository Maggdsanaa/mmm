package com.uniatt.student

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.uniatt.core.EcKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * مفتاح الجهاز: يُنشأ داخل Android Keystore ولا يمكن استخراج المفتاح الخاص.
 * لا يُحذف أبدًا (حتى عند «مسح البيانات») كي لا يستطيع الطالب فكّ ارتباطه بجهازه بمجرد المسح.
 */
object DeviceKey {
    private const val ALIAS = "uniatt_student_key"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** النقطة الخام (65 بايت) للمفتاح العام. */
    fun publicRaw(): ByteArray {
        val s = ks()
        if (!s.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            g.generateKeyPair()
        }
        return EcKey.rawFromX509(s.getCertificate(ALIAS).publicKey.encoded)
            ?: throw IllegalStateException("unexpected public key encoding")
    }

    fun sign(data: ByteArray): ByteArray {
        val pk = ks().getKey(ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run { initSign(pk); update(data); sign() }
    }
}
