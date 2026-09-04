/**
 * =================================================================================
 * [Context Header]
 * * @file: RomPermissionDetector.kt
 * * @role: Domain Skill
 * * @description: 统一封装 ROM 私有权限（自启动/后台弹出/关联启动）的状态探测策略。
 * * @responsibilities:
 * 1. 提供跨机型的权限探测入口，优先使用可读取的系统状态。
 * 2. 在可探测时返回明确 true/false，不可探测时返回 null 交给人工复核。
 * * @context:
 * - 上游: HomeFragment, RulesFragment
 * - 下游: AppOpsManager / 系统权限状态
 * * @constraints (必读):
 * 1. 不引入 UI 依赖，不弹窗。
 * 2. 兼容不同 Android 版本，异常必须兜底。
 * 3. 不得阻塞主线程，仅读取轻量系统状态。
 * =================================================================================
 */
package com.zhizhi.app.manager

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

object RomPermissionDetector {

    private const val TAG = "RomPermissionDetector"

    enum class ManualPermission {
        AUTO_START,
        BG_POPUP,
        ASSOC_START,
    }

    fun detectManualPermission(context: Context, permission: ManualPermission): Boolean? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val (candidates, strictTokens) = when (permission) {
            ManualPermission.AUTO_START -> buildAutoStartCandidates(manufacturer) to listOf(
                "auto_start",
                "auto_startup",
                "startup"
            )
            ManualPermission.BG_POPUP -> buildBgPopupCandidates(manufacturer) to listOf(
                "background_popup",
                "background_start_activity",
                "bg_start_activity",
                "popup"
            )
            ManualPermission.ASSOC_START -> buildAssocStartCandidates(manufacturer) to listOf(
                "associate",
                "association",
                "bg_start_activity",
                "background_start"
            )
        }
        val result = checkAppOpsCandidates(context, candidates, strictTokens)
        Log.d(TAG, "detectManualPermission permission=$permission manufacturer=$manufacturer result=$result")
        return result
    }

    private fun buildAutoStartCandidates(manufacturer: String): List<String> {
        val base = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            base.add("android:run_in_background")
            base.add("android:start_foreground")
        }
        base.add("android:auto_start")
        base.add("android:auto_startup")

        if (manufacturer.contains("xiaomi")) {
            base.add("miui:auto_start")
            base.add("miui:background_start_activity")
        }
        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            base.add("vivo:bg_start_activity")
            base.add("vivo:background_start")
        }
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            base.add("huawei:auto_start")
            base.add("huawei:startup")
        }
        if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            base.add("oppo:auto_start")
            base.add("oppo:background_start_activity")
        }
        return base
    }

    private fun buildBgPopupCandidates(manufacturer: String): List<String> {
        val base = mutableListOf<String>()
        base.add("android:background_start_activity")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            base.add("android:run_any_in_background")
        }

        if (manufacturer.contains("xiaomi")) {
            base.add("miui:background_popup")
            base.add("miui:background_start_activity")
        }
        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            base.add("vivo:bg_start_activity")
            base.add("vivo:background_popup")
        }
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            base.add("huawei:background_popup")
        }
        if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            base.add("oppo:background_popup")
            base.add("oppo:background_start_activity")
        }
        return base
    }

    private fun buildAssocStartCandidates(manufacturer: String): List<String> {
        val base = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            base.add("android:start_foreground")
            base.add("android:run_in_background")
        }
        base.add("android:associate_start")
        base.add("android:association_start")

        if (manufacturer.contains("xiaomi")) {
            base.add("miui:associate_start")
            base.add("miui:auto_start")
        }
        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            base.add("vivo:associate_start")
            base.add("vivo:bg_start_activity")
        }
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            base.add("huawei:associate_start")
            base.add("huawei:startup")
        }
        if (manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            base.add("oppo:associate_start")
            base.add("oppo:auto_start")
        }
        return base
    }

    private fun checkAppOpsCandidates(
        context: Context,
        opNames: List<String>,
        strictTokens: List<String>
    ): Boolean? {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return null
        var hasExplicitDeny = false
        var hasDeterministicResult = false

        for (opName in opNames.distinct()) {
            val normalizedName = opName.lowercase()
            val isStrictlyRelevant = strictTokens.any { normalizedName.contains(it) }
            if (!isStrictlyRelevant) {
                Log.d(TAG, "skip candidate op=$opName reason=token_mismatch")
                continue
            }

            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(opName, Process.myUid(), context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(opName, Process.myUid(), context.packageName)
                }
            }.getOrNull()

            if (mode == null) {
                Log.d(TAG, "candidate op=$opName mode=null(unavailable)")
                continue
            }

            Log.d(TAG, "candidate op=$opName mode=$mode")

            when (mode) {
                AppOpsManager.MODE_ALLOWED -> return true
                AppOpsManager.MODE_IGNORED,
                AppOpsManager.MODE_ERRORED -> {
                    hasDeterministicResult = true
                    hasExplicitDeny = true
                }
                AppOpsManager.MODE_DEFAULT -> {
                    // MODE_DEFAULT 不足以直接判定开启/关闭，继续尝试其他候选。
                }
                else -> {
                    hasDeterministicResult = true
                }
            }
        }

        if (hasExplicitDeny) return false
        if (hasDeterministicResult) return false
        Log.d(TAG, "checkAppOpsCandidates no_deterministic_result")
        return null
    }
}
