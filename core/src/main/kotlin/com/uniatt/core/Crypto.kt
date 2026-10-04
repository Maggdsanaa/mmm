package com.uniatt.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Hex {
    fun enc(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun dec(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

object Hmac {
    fun sha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** مقارنة بزمن ثابت. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

object Kdf {
    const val STUDENT_ITER = 10_000      // يُحسب مرة واحدة لكل طالب (عند توليد الكود وعند تفعيل هاتفه)
    const val DOCTOR_ITER = 100_000      // لفتح/إغلاق ملف الكشف

    fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    /** مفتاح الطالب المشتق من كوده؛ يحتفظ به هاتف الطالب وكشف الدكتور، ولا يخرج الكود نفسه. */
    fun studentKey(code: String): ByteArray =
        pbkdf2(code, "uniatt-student|${Codes.tag(code)}".toByteArray(Charsets.UTF_8), STUDENT_ITER)
}

/**
 * صندوق مشفّر AES-256-GCM بمفتاح مشتق من كود الدكتور.
 * التخطيط: magic(4) | hintLen(1) | hint | salt(16) | iv(12) | ciphertext+tag
 * الـ hint (معرّف الدكتور) ظاهر لكنه موثَّق ضمن AAD، فأي تعديل فيه أو في المحتوى يُفشل الفتح.
 */
object Box {
    val MAGIC_ROSTER: ByteArray = "UAR1".toByteArray(Charsets.US_ASCII)
    val MAGIC_ATTEND: ByteArray = "UAT1".toByteArray(Charsets.US_ASCII)
    val MAGIC_BACKUP: ByteArray = "UAB1".toByteArray(Charsets.US_ASCII)   // نسخة المسؤول الاحتياطية
    private const val SALT = 16
    private const val IV = 12
    private const val TAG = 16

    fun seal(magic: ByteArray, hint: String, plain: ByteArray, code: String, rnd: SecureRandom = SecureRandom()): ByteArray {
        val h = hint.toByteArray(Charsets.UTF_8)
        require(h.size <= 255) { "hint too long" }
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val header = magic + byteArrayOf(h.size.toByte()) + h
        return header + salt + iv + cipher(Cipher.ENCRYPT_MODE, code, salt, iv, header).doFinal(plain)
    }

    fun hint(magic: ByteArray, blob: ByteArray): String? {
        if (blob.size < 5 || !blob.copyOfRange(0, 4).contentEquals(magic)) return null
        val n = blob[4].toInt() and 0xFF
        if (blob.size < 5 + n + SALT + IV + TAG) return null
        return String(blob, 5, n, Charsets.UTF_8)
    }

    /** null = ملف تالف أو كود خاطئ. */
    fun open(magic: ByteArray, blob: ByteArray, code: String): ByteArray? = try {
        hint(magic, blob) ?: throw IllegalArgumentException()
        val n = blob[4].toInt() and 0xFF
        val hdrEnd = 5 + n
        val header = blob.copyOfRange(0, hdrEnd)
        val salt = blob.copyOfRange(hdrEnd, hdrEnd + SALT)
        val iv = blob.copyOfRange(hdrEnd + SALT, hdrEnd + SALT + IV)
        val ct = blob.copyOfRange(hdrEnd + SALT + IV, blob.size)
        cipher(Cipher.DECRYPT_MODE, code, salt, iv, header).doFinal(ct)
    } catch (e: Exception) { null }

    private fun cipher(mode: Int, code: String, salt: ByteArray, iv: ByteArray, aad: ByteArray): Cipher {
        val key = Kdf.pbkdf2(code, salt, Kdf.DOCTOR_ITER)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
        }
    }
}

/** المفتاح العام P-256 يُرسل كنقطة خام (65 بايت) لتوفير حجم الرسالة، ويُعاد تغليفه بترويسة X.509 الثابتة. */
object EcKey {
    const val RAW_SIZE = 65
    private val X509_HEADER = Hex.dec("3059301306072a8648ce3d020106082a8648ce3d030107034200") // 26 بايت

    fun rawFromX509(enc: ByteArray): ByteArray? =
        if (enc.size == 91 && enc.copyOfRange(0, 26).contentEquals(X509_HEADER)) enc.copyOfRange(26, 91) else null

    fun toX509(raw: ByteArray): ByteArray = X509_HEADER + raw
}

object SigVerifier {
    fun verify(pubRaw: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        if (pubRaw.size != EcKey.RAW_SIZE || pubRaw[0] != 4.toByte()) false
        else {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(EcKey.toX509(pubRaw)))
            Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(sig) }
        }
    } catch (e: Exception) { false }
}
