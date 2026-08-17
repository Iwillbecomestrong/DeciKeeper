package com.ironwill.app.manager

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 应用安全分类器（自 CreditManager 拆出，行为零变化）。
 * isLauncher / isInputMethod / isCurrentInputMethod / isSafeSystemOrVendor 纯判定逻辑。
 * 外部仍通过 CreditManager.isLauncher(...) 等转发调用，对外接口零改动。
 */
object AppSafetyClassifiers {

    fun isLauncher(pkg: String): Boolean {
        return pkg.contains("launcher") || 
               pkg.contains("home") || 
               pkg.contains("miui.home") ||
               pkg.contains("quickstep") ||
               pkg.contains("nexuslauncher")
    }

    fun isInputMethod(context: Context, pkg: String): Boolean {
        val p = pkg.lowercase()
        // 🚀 [Optimization] Pre-filter based on common fragments to avoid regex/heavy string ops
        if (p.contains("ime") || p.contains("input") || p.contains("key") || 
            p.contains("sogou") || p.contains("baidu") || p.contains("iflytek") || p.contains("wetype")) {
            return true
        }
        // 双重保险
        return isCurrentInputMethod(context, pkg)
    }

    fun isSafeSystemOrVendor(pkg: String): Boolean {
        // 这是一个兜底策略。
        // 如果代码运行到这里，说明它【不是】桌面、设置、多任务。
        // 此时，如果是系统或厂商的包，我们应该放行。
        val p = pkg.lowercase()

        // 🚀 [Fix] 明确排除浏览器 (Browser)，即使是厂商自带的也不应判定为"安全系统服务"
        // 这样可以确保 TimeWindow/Blacklist 能正常拦截浏览器
        if (p.contains("browser") || p.contains("chrome") || p.contains("explorer")) {
            return false
        }

        return p == "android" ||
               p.contains("systemui") ||
               p.contains("telecom") ||
               p.contains("incallui") ||
               // 🟢 [Fix] 允许厂商的底层服务 (如 Vivo 的 com.vivo.upslide 手势服务)
               p.startsWith("com.vivo.") || 
               p.startsWith("com.bbk.") ||  // Vivo/Oppo 母公司
               p.startsWith("com.oppo.") ||
               p.startsWith("com.miui.") ||
               p.startsWith("com.huawei.")
               // p.startsWith("com.google.android.") ||  // 🔴 Removed: Too broad (e.g. YouTube, Gmail)
               // p.startsWith("com.android.")            // 🔴 Removed: Too broad (e.g. Chrome)
    }

    private fun isCurrentInputMethod(context: Context, packageName: String): Boolean {
        try {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (defaultIme != null && defaultIme.contains(packageName)) {
                return true
            }
        } catch (e: Exception) {
            Log.e("CreditManager", "isCurrentInputMethod failed", e)
        }
        return false
    }
}
