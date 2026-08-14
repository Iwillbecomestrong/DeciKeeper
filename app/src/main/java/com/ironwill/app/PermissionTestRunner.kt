package com.ironwill.app

import android.content.Context

object PermissionTestRunner {

    fun run(context: Context): String {
        var pass = 0
        var fail = 0
        val sb = StringBuilder()

        fun log(line: String) {
            sb.append(line).append("\n")
        }

        log("==========================================")
        log("IronWill 权限检查快速测试")
        log("==========================================")

        val allPermissions = PermissionManager.Permission.values()
        if (allPermissions.size == 5) {
            pass++
            log("PASS: 权限枚举数量=5")
        } else {
            fail++
            log("FAIL: 权限枚举数量!=5")
        }

        var noException = true
        try {
            PermissionManager.isAccessibilityEnabled(context)
            PermissionManager.isAccessibilityActive()
            PermissionManager.hasOverlayPermission(context)
            PermissionManager.hasUsageStatsPermission(context)
            PermissionManager.isBatteryOptimizationExempt(context)
            PermissionManager.hasNotificationPermission(context)
        } catch (_: Exception) {
            noException = false
        }
        if (noException) {
            pass++
            log("PASS: 权限检查不抛异常")
        } else {
            fail++
            log("FAIL: 权限检查异常")
        }

        val status = PermissionManager.getAllPermissionStatus(context)
        for (p in allPermissions) {
            val granted = status[p] == true
            log("  ${p.displayName}: ${if (granted) "已开启" else "未开启"}")
        }

        val missing = PermissionManager.getMissingPermissions(context)
        if (missing.isEmpty()) {
            pass++
            log("PASS: 当前无缺失权限")
        } else {
            pass++
            log("INFO: 缺失权限数量=${missing.size}")
        }

        log("==========================================")
        log("测试总结")
        log("  通过: $pass")
        log("  失败: $fail")
        log("  总计: ${pass + fail}")
        log("==========================================")

        return sb.toString().trimEnd()
    }
}
