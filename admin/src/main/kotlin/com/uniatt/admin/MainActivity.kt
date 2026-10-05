package com.uniatt.admin

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AdminVM by viewModels()
    private var cm: ConnectivityManager? = null
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // أول ما يتصل الهاتف بالإنترنت (واي فاي/بيانات) نزامن تلقائيًا
        override fun onAvailable(network: Network) { runOnUiThread { vm.syncNow() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(applicationContext)
        setContent { AdminApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.refresh()
        vm.syncNow()
        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onStop()
    }
}
