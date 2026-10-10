package com.uniatt.doctor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.uniatt.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * الحضور التلقائي بالبلوتوث (جهة الدكتور): أثناء الجلسة يُعلن خدمة GATT ثابتة؛ هواتف الطلاب تلتقطها وتتصل تلقائيًا
 * وتنفّذ التحدّي والإجابة (انظر BleProtocol). التحقق نفسه المستخدم في NFC (AttendanceEvaluator).
 * صلاحيتا BLUETOOTH_ADVERTISE/CONNECT (أندرويد 12+) تُطلبان من MainActivity قبل [start].
 */
@SuppressLint("MissingPermission")
class BleDoctor(
    private val ctx: Context,
    private val evaluator: AttendanceEvaluator,
    private val session: () -> ActiveSession?,
    private val onEvent: (ScanEvent) -> Unit,
    private val onClient: () -> Unit
) {
    private val uService = UUID.fromString(BleProtocol.SERVICE_UUID)
    private val uChallenge = UUID.fromString(BleProtocol.CHALLENGE_UUID)
    private val uReply = UUID.fromString(BleProtocol.REPLY_UUID)
    private val uResult = UUID.fromString(BleProtocol.RESULT_UUID)

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val challenges = QrChallenges()
    private var scope: CoroutineScope? = null

    private class Peer { val asm = ProfileAssembler(); @Volatile var result: ByteArray? = null }
    private val peers = ConcurrentHashMap<String, Peer>()

    @Volatile var running = false; private set

    /** يعيد رسالة خطأ بالعربية أو null عند النجاح. */
    fun start(): String? {
        if (running) return null
        try {
            val mgr = ctx.getSystemService(BluetoothManager::class.java) ?: return "هذا الجهاز لا يدعم البلوتوث"
            val ad = mgr.adapter ?: return "هذا الجهاز لا يدعم البلوتوث"
            if (!ad.isEnabled) return "البلوتوث مغلق — فعّله ثم أعد المحاولة"
            advertiser = ad.bluetoothLeAdvertiser ?: return "هذا الهاتف لا يدعم الإعلان عبر البلوتوث LE (استخدم NFC أو QR)"
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            server = mgr.openGattServer(ctx, callback) ?: return "تعذّر تشغيل خادم البلوتوث"

            val svc = BluetoothGattService(uService, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(BluetoothGattCharacteristic(uChallenge, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            svc.addCharacteristic(BluetoothGattCharacteristic(uReply, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            svc.addCharacteristic(BluetoothGattCharacteristic(uResult, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            running = true
            server!!.addService(svc)              // الإعلان يبدأ في onServiceAdded
            return null
        } catch (e: SecurityException) {
            running = false
            return "صلاحية البلوتوث مرفوضة — اسمح بها من إعدادات التطبيق"
        } catch (e: Exception) {
            running = false
            return "تعذّر تشغيل البلوتوث (${e.javaClass.simpleName})"
        }
    }

    fun stop() {
        running = false
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null; advertiser = null
        scope?.cancel(); scope = null
        peers.clear(); challenges.clear()
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onEvent(ScanEvent(false, "تعذّر بدء إعلان البلوتوث (رمز $errorCode). جرّب إيقاف البلوتوث وتشغيله."))
        }
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(uService)).build()
        try { advertiser?.startAdvertising(settings, data, advCallback) } catch (e: Exception) {
            onEvent(ScanEvent(false, "تعذّر بدء إعلان البلوتوث (${e.javaClass.simpleName})"))
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS && running) startAdvertising()
            else onEvent(ScanEvent(false, "تعذّر تسجيل خدمة البلوتوث (رمز $status)"))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) onClient()
            if (newState == BluetoothProfile.STATE_DISCONNECTED) peers.remove(device.address)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val s = server ?: return
            when (ch.uuid) {
                uChallenge -> {
                    val sess = session()
                    if (sess == null || System.currentTimeMillis() > sess.endsAt) {
                        s.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null); return
                    }
                    respond(device, requestId, offset, BleProtocol.challengeBytes(challenges.issue(sess.sessionId)))
                }
                uResult -> respond(device, requestId, offset, peers[device.address]?.result ?: byteArrayOf(BleProtocol.NO_RESULT))
                else -> s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val s = server ?: return
            if (ch.uuid != uReply || preparedWrite || offset != 0 || value == null) {
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val peer = peers.getOrPut(device.address) { Peer() }
            val payload = BleProtocol.add(peer.asm, value)
            if (payload == null) {                         // قطعة وسطى: نؤكد الاستلام فقط
                if (responseNeeded) s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }
            // آخر قطعة: التحقق وتسجيل الحضور خارج خيط البلوتوث، ثم نردّ على الكتابة
            scope?.launch {
                val res = try { handle(payload) } catch (e: Exception) { BleProtocol.encodeResult(Status.EXPIRED, null) }
                peer.result = res
                if (responseNeeded) try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (_: Exception) {}
            }
        }
    }

    private fun respond(device: BluetoothDevice, id: Int, offset: Int, value: ByteArray) {
        val part = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        server?.sendResponse(device, id, BluetoothGatt.GATT_SUCCESS, offset, part)
    }

    private suspend fun handle(payload: ByteArray): ByteArray {
        val sess = session() ?: return BleProtocol.encodeResult(Status.EXPIRED, null)
        val pr = BleProtocol.parseReplyPayload(payload) ?: return BleProtocol.encodeResult(Status.BAD_PROOF, null)
        val req = challenges.find(pr.nonce) ?: return BleProtocol.encodeResult(Status.EXPIRED, null)
        if (!Protocol.uuidToBytes(sess.sessionId).contentEquals(req.sessionId)) return BleProtocol.encodeResult(Status.EXPIRED, null)
        val out = evaluator.evaluate(sess, pr.reply, req)
        onEvent(ScanEvent(out.status == Status.OK, "بلوتوث: " + AttendanceEvaluator.describe(out, pr.reply.tag)))
        return BleProtocol.encodeResult(out.status, out.profile?.encode())
    }
}
