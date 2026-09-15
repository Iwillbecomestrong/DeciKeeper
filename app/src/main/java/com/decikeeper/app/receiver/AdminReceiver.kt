package com.decikeeper.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.decikeeper.app.data.LogExporter

/**
 * 设备管理器广播接收器。
 * 允许应用被系统识别为 Device Admin，防止在系统桌面/应用管理中被轻易卸载。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin Enabled")
        LogExporter.logEvent(TAG, "Device Admin Enabled by user")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin Disabled")
        LogExporter.logEvent(TAG, "Device Admin Disabled by user")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Log.w(TAG, "Device Admin Disable Requested")
        LogExporter.logEvent(TAG, "Device Admin Disable Requested")
        return "取消激活设备管理器可能导致防卸载与防关机保护失效！"
    }

    companion object {
        private const val TAG = "AdminReceiver"
    }
}
