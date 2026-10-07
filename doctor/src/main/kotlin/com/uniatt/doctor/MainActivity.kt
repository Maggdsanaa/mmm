package com.uniatt.doctor

import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: DoctorVM by viewModels()
    private var nfc: NfcAdapter? = null
    private lateinit var reader: AttendanceReader
    private var tick by mutableIntStateOf(0)
    private var cm: ConnectivityManager? = null
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // أول ما يتصل الهاتف بالإنترنت نزامن تلقائيًا (كشف جديد + رفع الحضور)
        override fun onAvailable(network: Network) { runOnUiThread { vm.syncNow() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        SyncWorker.schedule(applicationContext)
        reader = AttendanceReader(vm.dao, vm::onScan) { vm.active.value }
        reader.scheduleOf = { id -> vm.scheduleText(id, withRoom = false) }
        reader.testMode = { vm.testMode.value }
        reader.onContact = { vm.onContact() }

        // وضع القارئ يعمل فقط أثناء ظهور الشاشة وجلسة نشطة
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    combine(vm.active, vm.testMode) { a, t -> a != null || t }.collect { if (it) enable() else disable() }
                } finally { disable() }
            }
        }
        setContent {
            DoctorApp(vm) {
                tick // قراءة الحالة لإعادة التركيب عند الرجوع من الإعدادات
                val a = nfc
                when {
                    a == null -> false to "هذا الجهاز لا يدعم NFC"
                    !a.isEnabled -> false to "NFC مغلق — فعّله من الإعدادات"
                    else -> true to "NFC جاهز"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.syncNow()
        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onResume() { super.onResume(); tick++ }

    private fun enable() {
        try {
            nfc?.enableReaderMode(
                this, reader,
                // NFC-A فقط (ما تُحاكيه هواتف HCE) + بلا فحص NDEF؛ وفحص حضور أبطأ ليبقى الاتصال مستقرًا
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            )
            vm.readerOn.value = true
        } catch (e: Exception) {
            vm.readerOn.value = false
            vm.onScan(ScanEvent(false, "تعذّر تشغيل وضع قراءة NFC (${e.javaClass.simpleName}): ${e.message ?: ""}"))
        }
    }
    private fun disable() {
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
        vm.readerOn.value = false
    }
}
