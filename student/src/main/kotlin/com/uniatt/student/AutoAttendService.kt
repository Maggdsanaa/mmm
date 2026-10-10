package com.uniatt.student

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * خدمة أمامية (بإشعار دائم) تُبقي البحث عن محاضرة الدكتور بالبلوتوث شغّالًا في الخلفية،
 * فيُسجَّل الحضور تلقائيًا دون فتح التطبيق. تبدأ من الشاشة الرئيسية بعد منح الصلاحيات.
 */
class AutoAttendService : Service() {
    private var ble: BleStudent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "التسجيل التلقائي", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("حضور - الطالب")
            .setContentText("التسجيل التلقائي بالبلوتوث شغّال")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, n)
        } catch (e: Exception) {
            ResultBus.ble.value = "تعذّر تشغيل الخدمة (${e.javaClass.simpleName})"
            stopSelf(); return START_NOT_STICKY
        }
        if (ble == null) {
            ble = BleStudent(applicationContext) { ResultBus.ble.value = it }
            val err = ble!!.start()
            if (err != null) { ble = null; ResultBus.ble.value = err; stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ble?.stop(); ble = null
        ResultBus.ble.value = "متوقف"
        super.onDestroy()
    }

    companion object {
        private const val CH = "auto_attend"
        fun start(ctx: Context) {
            val i = Intent(ctx, AutoAttendService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AutoAttendService::class.java)) }
    }
}
