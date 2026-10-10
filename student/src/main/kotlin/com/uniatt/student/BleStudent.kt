package com.uniatt.student

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.uniatt.core.*
import java.util.UUID
import kotlin.random.Random

/**
 * الحضور التلقائي بالبلوتوث (جهة الطالب): يبحث عن إعلان الدكتور (خدمة GATT ثابتة)، يتصل، يقرأ التحدّي،
 * يكتب الإجابة (HMAC بكوده + توقيع Keystore) مجزّأة، ثم يقرأ النتيجة والبيانات ويقطع الاتصال.
 * اتصال واحد في كل مرة؛ تأخير عشوائي قبل الاتصال كي لا تتزاحم هواتف القاعة على هاتف الدكتور؛ إعادة محاولة تلقائية.
 */
@SuppressLint("MissingPermission")
class BleStudent(private val ctx: Context, private val onState: (String) -> Unit) {
    private val uService = UUID.fromString(BleProtocol.SERVICE_UUID)
    private val uChallenge = UUID.fromString(BleProtocol.CHALLENGE_UUID)
    private val uReply = UUID.fromString(BleProtocol.REPLY_UUID)
    private val uResult = UUID.fromString(BleProtocol.RESULT_UUID)

    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var running = false
    private var busy = false
    private var gatt: BluetoothGatt? = null
    private var current: BluetoothDevice? = null

    private val cooldown = HashMap<String, Long>()       // عنوان الدكتور -> لا نتصل قبل هذا الوقت
    private val failures = HashMap<String, Int>()
    private val doneSessions = LinkedHashSet<String>()    // جلسات سُجّل فيها الحضور فعلًا

    private var mtu = 23
    private var sessionId = ""
    private var chunks: List<ByteArray> = emptyList()
    private var next = 0
    private val timeout = Runnable { finish(false, "انتهت مهلة الاتصال — ستُعاد المحاولة") }

    fun start(): String? {
        if (running) return null
        try {
            val mgr = ctx.getSystemService(BluetoothManager::class.java) ?: return "هذا الجهاز لا يدعم البلوتوث"
            val ad = mgr.adapter ?: return "هذا الجهاز لا يدعم البلوتوث"
            if (!ad.isEnabled) return "البلوتوث مغلق — فعّله ليعمل التسجيل التلقائي"
            scanner = ad.bluetoothLeScanner ?: return "تعذّر تشغيل البحث عبر البلوتوث"
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uService)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            scanner!!.startScan(listOf(filter), settings, scanCb)
            running = true
            onState("يبحث عن محاضرة قريبة…")
            return null
        } catch (e: SecurityException) {
            return "صلاحية البلوتوث مرفوضة — اسمح بها من إعدادات التطبيق"
        } catch (e: Exception) {
            return "تعذّر تشغيل البحث (${e.javaClass.simpleName})"
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        scanner = null
        closeGatt()
        busy = false
        onState("متوقف")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || busy) return
            val addr = result.device.address
            if ((cooldown[addr] ?: 0L) > System.currentTimeMillis()) return
            if (result.rssi < -92) return                            // إشارة ضعيفة جدًا: على الأرجح بعيد
            busy = true
            current = result.device
            onState("وُجدت محاضرة — جارٍ التسجيل…")
            // تأخير عشوائي كي لا يتصل كل طلاب القاعة في اللحظة نفسها
            main.postDelayed({ if (running) connect(result.device) else busy = false }, Random.nextLong(0, 6000))
        }

        override fun onScanFailed(errorCode: Int) { onState("تعذّر البحث بالبلوتوث (رمز $errorCode)") }
    }

    private fun connect(device: BluetoothDevice) {
        mtu = 23; sessionId = ""; chunks = emptyList(); next = 0
        main.postDelayed(timeout, 25_000)
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, gattCb)
        if (gatt == null) finish(false, "تعذّر الاتصال")
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun finish(success: Boolean, msg: String, session: String? = null) {
        main.removeCallbacks(timeout)
        closeGatt()
        val addr = current?.address
        val now = System.currentTimeMillis()
        if (addr != null) {
            if (success) { failures.remove(addr); cooldown[addr] = now + (if (session != null) 5 * 60_000L else 60_000L) }
            else {
                val n = (failures[addr] ?: 0) + 1; failures[addr] = n
                cooldown[addr] = now + if (n >= 6) 2 * 60_000L else Random.nextLong(3_000L, 8_000L * n)
            }
        }
        busy = false
        onState(msg)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { finish(false, "تعذّر الاتصال (رمز $status) — ستُعاد المحاولة"); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(185)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && busy) finish(false, "انقطع الاتصال — ستُعاد المحاولة")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) this@BleStudent.mtu = mtu
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val ch = g.getService(uService)?.getCharacteristic(uChallenge)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) { finish(false, "الجهاز المقابل ليس هاتف دكتور"); return@post }
                if (!g.readCharacteristic(ch)) finish(false, "تعذّرت قراءة التحدّي")
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) { val v = ch.value; main.post { handleRead(g, ch.uuid, v, status) } }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { handleRead(g, ch.uuid, value, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { finish(false, "فشل إرسال الإجابة (رمز $status) — ستُعاد المحاولة"); return@post }
                if (next < chunks.size) writeNext(g)
                else {
                    val res = g.getService(uService)?.getCharacteristic(uResult)
                    if (res == null || !g.readCharacteristic(res)) finish(false, "تعذّرت قراءة النتيجة")
                }
            }
        }
    }

    private fun handleRead(g: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
        if (g != gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            finish(false, if (uuid == uChallenge) "لا توجد جلسة حضور نشطة عند الدكتور" else "فشل قراءة النتيجة")
            return
        }
        when (uuid) {
            uChallenge -> {
                val req = BleProtocol.parseChallenge(value) ?: run { finish(false, "تحدٍّ غير صالح"); return }
                sessionId = Protocol.bytesToUuid(req.sessionId).toString()
                if (sessionId in doneSessions) { finish(true, "✓ سُجّل حضورك في هذه المحاضرة", sessionId); return }
                val payload = try { StudentQr.payload(ctx, req) } catch (e: Exception) { finish(false, "فعّل التطبيق بكودك أولًا"); return }
                chunks = BleProtocol.chunk(payload, (mtu - 5).coerceIn(10, BleProtocol.MAX_CHUNK))
                next = 0
                writeNext(g)
            }
            uResult -> {
                val r = BleProtocol.decodeResult(value) ?: run { finish(false, "لم تصل نتيجة من الدكتور"); return }
                val store = StudentStore(ctx)
                store.addHistory(HistoryItem(System.currentTimeMillis(), sessionId, r.status))
                if (Status.ok(r.status)) {
                    doneSessions.add(sessionId); if (doneSessions.size > 50) doneSessions.remove(doneSessions.first())
                    r.profile?.let { StudentProfile.decode(it)?.let { p -> store.mergeProfile(p) } }
                }
                finish(Status.ok(r.status), Status.arabic(r.status), if (Status.ok(r.status)) sessionId else null)
            }
        }
    }

    private fun writeNext(g: BluetoothGatt) {
        val ch = g.getService(uService)?.getCharacteristic(uReply) ?: run { finish(false, "الخدمة غير مكتملة"); return }
        val data = chunks[next++]
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION") run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = data
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) finish(false, "تعذّر إرسال الإجابة")
    }
}
