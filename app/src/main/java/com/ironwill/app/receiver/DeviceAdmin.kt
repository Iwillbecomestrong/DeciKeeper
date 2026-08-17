/**
 * =================================================================================
 * [Context Header]
 * * @file: DeviceAdmin.kt
 * * @role: Domain Skill
 * * @description: 设备管理权限接入与策略回调。
 * * @responsibilities:
 * 1. 接收设备管理员状态变更。
 * 2. 支撑必要的系统级策略。
 * * @context:
 * - 上游: 系统设备管理器
 * - 下游: CoreService
 * * @constraints (必读):
 * 1. 仅处理系统回调。
 * 2. 不引入 UI 依赖。
 * 3. 处理权限缺失情况。
 * =================================================================================
 */
package com.ironwill.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "防卸载保护已激活", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "防卸载保护已撤销", Toast.LENGTH_SHORT).show()
    }
}
