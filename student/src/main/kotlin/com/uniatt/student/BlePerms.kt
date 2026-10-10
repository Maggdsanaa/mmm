package com.uniatt.student

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** صلاحيات التسجيل التلقائي بالبلوتوث حسب إصدار أندرويد. */
fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
    else add(Manifest.permission.ACCESS_FINE_LOCATION)           // أندرويد 11 وأقل: البحث عبر BLE يتطلب الموقع
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)   // لإظهار إشعار الخدمة (اختياري)
}.toTypedArray()

/** الصلاحيات الضرورية فعلًا (الإشعارات اختيارية). */
fun blePermissionsGranted(ctx: Context): Boolean =
    blePermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
