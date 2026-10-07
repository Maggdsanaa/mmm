package com.uniatt.student

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StudentStore(this)
        setContent { StudentApp(store, tick) }
    }

    override fun onResume() {
        super.onResume()
        tick++
        // يجعل هذا التطبيق هو الخدمة المفضلة أثناء ظهوره (أكثر موثوقية)
        NfcAdapter.getDefaultAdapter(this)?.let {
            try {
                CardEmulation.getInstance(it)
                    .setPreferredService(this, ComponentName(this, AttendanceApduService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let {
            try { CardEmulation.getInstance(it).unsetPreferredService(this) } catch (_: Exception) {}
        }
    }
}
