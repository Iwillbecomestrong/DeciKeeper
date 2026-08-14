/**
 * =================================================================================
 * [Context Header]
 * * @file: PwaBypassManager.kt
 * * @role: Domain Skill
 * * @description: 识别并管理 Chrome/WebAPK 的 PWA 快捷方式放行会话。
 * * @responsibilities:
 * 1. 解析 Accessibility 事件中的 PWA 特征并建立短时会话。
 * 2. 为 Monitor/Core 拦截链路提供二次放行判定。
 * * @context:
 * - 上游: CoreService, MonitorService
 * - 下游: SharedPreferences, LogExporter
 * * @constraints (必读):
 * 1. 必须线程安全，避免跨服务状态竞争。
 * 2. 判定仅用于白名单 PWA，不可放宽普通浏览器拦截。
 * 3. 会话必须短时自动过期，防止放行泄漏。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context

object PwaBypassManager {

    private const val PREFS_NAME = "pwa_bypass"
    private const val KEY_ALLOWED_PWA_KEYWORDS = "allowed_pwa_keywords"
    private const val KEY_ALLOWED_WEBAPK_PACKAGES = "allowed_webapk_packages"

    private const val SESSION_TTL_MS = 8000L
    private const val BYPASS_JITTER_GRACE_MS = 2500L

    private val defaultAllowedKeywords = setOf(
        "gemini",
        "authenticator",
        "chatgpt",
        "notion",
        "twitter",
        " x ",
        "x.com",
        "keep",
        "photos"
    )

    @Volatile
    private var lastSessionAt: Long = 0L

    @Volatile
    private var lastWebApkPackage: String? = null

    @Volatile
    private var lastActivityClass: String? = null

    @Volatile
    private var lastBypassAllowedAt: Long = 0L

    @Volatile
    private var lastBypassAllowedPackage: String? = null

    private val pwaActivityHints = listOf(
        "webapk",
        "webappactivity",
        "sametaskwebapkactivity",
        "shell_apk",
        "webapps.",
        "webapplauncheractivity"
    )

    fun observeAccessibilitySignal(
        context: Context,
        packageName: String,
        className: String,
        eventText: String
    ) {
        val normalizedPkg = packageName.lowercase()
        val normalizedClass = className.lowercase()
        val normalizedText = eventText.lowercase()

        val hasChromiumSignal =
            isChromiumWebApkPackage(normalizedPkg) || isChromiumPwaActivity(normalizedClass)
        if (!hasChromiumSignal) return

        val keywordAllowed = matchesAllowedKeyword(normalizedText, getAllowedKeywords(context))
        val packageAllowed = isChromiumWebApkPackage(normalizedPkg) &&
            getAllowedWebApkPackages(context).contains(normalizedPkg)
        val localWebApkTrusted = isChromiumWebApkPackage(normalizedPkg)

        if (!keywordAllowed && !packageAllowed && !localWebApkTrusted) {
            return
        }

        lastSessionAt = System.currentTimeMillis()
        lastActivityClass = className
        if (isChromiumWebApkPackage(normalizedPkg)) {
            lastWebApkPackage = normalizedPkg
        }

        LogExporter.logEvent(
            "PwaBypass",
            "[SESSION] allow pwa signal pkg=$packageName class=$className keywordAllowed=$keywordAllowed packageAllowed=$packageAllowed localWebApkTrusted=$localWebApkTrusted"
        )
    }

    fun observeUsageEventSignal(
        context: Context,
        packageName: String,
        className: String
    ) {
        val normalizedPkg = packageName.lowercase()
        val normalizedClass = className.lowercase()

        val isWebApkPkg = isChromiumWebApkPackage(normalizedPkg)
        val isChromeWebappLauncher = isChromeFamilyPackage(normalizedPkg) && isChromiumPwaActivity(normalizedClass)
        if (!isWebApkPkg && !isChromeWebappLauncher) return

        val packageAllowed = isWebApkPkg && getAllowedWebApkPackages(context).contains(normalizedPkg)
        val localWebApkTrusted = isWebApkPkg

        if (!packageAllowed && !localWebApkTrusted && !isChromeWebappLauncher) {
            return
        }

        lastSessionAt = System.currentTimeMillis()
        lastActivityClass = className
        if (isWebApkPkg) {
            lastWebApkPackage = normalizedPkg
        }

        LogExporter.logEvent(
            "PwaBypass",
            "[SESSION_USAGE] allow pwa signal pkg=$packageName class=$className localWebApkTrusted=$localWebApkTrusted launcherSignal=$isChromeWebappLauncher packageAllowed=$packageAllowed"
        )
    }

    fun shouldBypassTopPackage(context: Context, topPackage: String): Boolean {
        val now = System.currentTimeMillis()
        val normalizedTopPackage = topPackage.lowercase()

        if (!isChromeFamilyPackage(normalizedTopPackage) && !isChromiumWebApkPackage(normalizedTopPackage)) {
            return false
        }

        if (!isSessionAlive(now)) {
            if (shouldBypassByJitterGrace(now, normalizedTopPackage)) {
                LogExporter.logEvent(
                    "PwaBypass",
                    "[GRACE] bypass by jitter grace pkg=$topPackage"
                )
                return true
            }
            return false
        }

        if (isChromiumWebApkPackage(normalizedTopPackage)) {
            val allowed = normalizedTopPackage == lastWebApkPackage
            markBypassDecision(now, normalizedTopPackage, allowed)
            return allowed
        }

        // Chrome family package fallback: rely on recently captured WebAPK/PWA activity signal.
        val allowed = lastWebApkPackage != null || isChromiumPwaActivity(lastActivityClass.orEmpty())
        markBypassDecision(now, normalizedTopPackage, allowed)
        return allowed
    }

    private fun isSessionAlive(now: Long = System.currentTimeMillis()): Boolean {
        return now - lastSessionAt <= SESSION_TTL_MS
    }

    internal fun shouldBypassByJitterGrace(
        nowMs: Long,
        normalizedTopPackage: String
    ): Boolean {
        return shouldBypassByJitterGrace(
            nowMs = nowMs,
            lastBypassAllowedAtMs = lastBypassAllowedAt,
            normalizedTopPackage = normalizedTopPackage,
            lastBypassAllowedPackage = lastBypassAllowedPackage
        )
    }

    internal fun shouldBypassByJitterGrace(
        nowMs: Long,
        lastBypassAllowedAtMs: Long,
        normalizedTopPackage: String,
        lastBypassAllowedPackage: String?
    ): Boolean {
        return normalizedTopPackage == lastBypassAllowedPackage &&
            nowMs - lastBypassAllowedAtMs <= BYPASS_JITTER_GRACE_MS
    }

    private fun markBypassDecision(nowMs: Long, normalizedTopPackage: String, allowed: Boolean) {
        if (!allowed) return
        lastBypassAllowedAt = nowMs
        lastBypassAllowedPackage = normalizedTopPackage
    }

    fun isChromiumWebApkPackage(packageName: String): Boolean {
        return packageName.startsWith("org.chromium.webapk.")
    }

    fun isChromiumPwaActivity(className: String): Boolean {
        val normalizedClass = className.lowercase()
        return pwaActivityHints.any { hint -> normalizedClass.contains(hint) }
    }

    fun isChromeFamilyPackage(packageName: String): Boolean {
        return packageName == "com.android.chrome" ||
            packageName == "com.google.android.googlequicksearchbox"
    }

    fun matchesAllowedKeyword(normalizedText: String, allowedKeywords: Set<String>): Boolean {
        if (normalizedText.isBlank()) return false
        return allowedKeywords.any { keyword -> normalizedText.contains(keyword.lowercase()) }
    }

    private fun getAllowedKeywords(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ALLOWED_PWA_KEYWORDS, defaultAllowedKeywords)
            ?.map { it.lowercase() }
            ?.toSet()
            ?: defaultAllowedKeywords
    }

    private fun getAllowedWebApkPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ALLOWED_WEBAPK_PACKAGES, emptySet())
            ?.map { it.lowercase() }
            ?.toSet()
            ?: emptySet()
    }
}
