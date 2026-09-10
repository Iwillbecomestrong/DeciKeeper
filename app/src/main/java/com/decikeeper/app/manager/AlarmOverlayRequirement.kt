package com.decikeeper.app.manager

import android.content.Context

/**
 * 闹钟挑战的显示前置条件。
 *
 * 闹钟挑战和锁界面都通过悬浮窗承载；没有该权限时不能退回到页面版挑战，
 * 否则 Activity 仍可能尝试添加 TYPE_APPLICATION_OVERLAY 而触发崩溃。
 */
object AlarmOverlayRequirement {
    const val TITLE = "需要悬浮窗权限"
    const val MESSAGE = "闹钟挑战必须通过悬浮窗显示，请先开启“显示在其他应用上层”权限。"
    const val MISSING_RUNTIME_MESSAGE = "闹钟已触发，但无法显示挑战。请先开启悬浮窗权限。"

    fun isGranted(context: Context): Boolean = PermissionManager.hasOverlayPermission(context)
}
