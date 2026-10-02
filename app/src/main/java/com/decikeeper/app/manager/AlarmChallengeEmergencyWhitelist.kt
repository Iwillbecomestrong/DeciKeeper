package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Global emergency-app allowlist used only while an alarm challenge is active.
 * The challenge itself remains active; this merely allows up to two explicitly
 * configured apps to be temporarily reachable.
 */
object AlarmChallengeEmergencyWhitelist {
    private const val PREFS = "alarm_challenge_emergency_whitelist"
    private const val KEY_PACKAGE_1 = "package_1"
    private const val KEY_PACKAGE_2 = "package_2"
    private const val LEGACY_KEY_PACKAGES = "packages"
    const val MAX_APPS = 2

    /** Pure policy seam used by JVM tests and foreground-enforcement code. */
    internal fun isPackageAllowedDuringChallenge(
        alarmActive: Boolean,
        foregroundPackage: String,
        selfPackage: String,
        allowedPackages: Set<String>,
        isPermissionRequestInProgress: Boolean = false
    ): Boolean {
        if (!alarmActive) return true
        if (foregroundPackage == selfPackage || allowedPackages.contains(foregroundPackage)) return true
        if (isCallUiPackage(foregroundPackage)) return true
        if (isPermissionRequestInProgress && isSystemPermissionController(foregroundPackage)) return true
        return false
    }

    /**
     * 系统通话界面（来电接听 / 拨出后的通话页）与拨号器不是同一个包，白名单只配了拨号器时
     * 通话界面会被当成“离开白名单”而被拉回挑战。接打电话是安全必需，挑战期间始终放行。
     */
    internal fun isCallUiPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p == "com.android.phone" ||
            p == "com.android.server.telecom" ||
            p.contains("incallui")
    }

    internal fun isSystemPermissionController(packageName: String): Boolean {
        return packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.vivo.permissionmanager" ||
            packageName == "com.oplus.securitypermission" ||
            packageName == "com.coloros.securitypermission" ||
            packageName == "com.android.packageinstaller"
    }

    /** Pure sanitization seam: preserve order, remove duplicates/dangerous/self, cap at two. */
    internal fun sanitizeSelection(
        packages: List<String>,
        selfPackage: String,
        dangerousPackages: Set<String>
    ): List<String> = packages.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .filter { it != selfPackage }
        .filterNot { dangerousPackages.contains(it) }
        .distinct()
        .take(MAX_APPS)
        .toList()

    fun getPackages(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ordered = listOfNotNull(
            prefs.getString(KEY_PACKAGE_1, null),
            prefs.getString(KEY_PACKAGE_2, null)
        )
        if (ordered.isNotEmpty() || prefs.contains(KEY_PACKAGE_1) || prefs.contains(KEY_PACKAGE_2)) {
            return sanitizeForDevice(context, ordered)
        }

        // One-time migration from the early StringSet implementation.
        val legacy = prefs.getStringSet(LEGACY_KEY_PACKAGES, null)
        if (legacy != null) {
            val migrated = sanitizeForDevice(context, legacy.toList())
            savePackages(context, migrated)
            return migrated
        }

        val defaults = resolveDefaultCommunicationApps(context)
        savePackages(context, defaults)
        return defaults
    }

    fun savePackages(context: Context, packages: Collection<String>) {
        val sanitized = sanitizeForDevice(context, packages.toList())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE_1, sanitized.getOrNull(0))
            .putString(KEY_PACKAGE_2, sanitized.getOrNull(1))
            .remove(LEGACY_KEY_PACKAGES)
            .apply()
    }

    fun isAllowed(context: Context, packageName: String, alarmActive: Boolean): Boolean =
        isPackageAllowedDuringChallenge(
            alarmActive = alarmActive,
            foregroundPackage = packageName,
            selfPackage = context.packageName,
            allowedPackages = getPackages(context).toSet(),
            isPermissionRequestInProgress = try {
                com.decikeeper.app.service.AlarmChallengeEmergencyGuardService.isPermissionRequestInProgress()
            } catch (_: Throwable) {
                false
            }
        )

    fun isSelectable(context: Context, packageName: String): Boolean =
        packageName.isNotBlank() &&
            packageName != context.packageName &&
            !dangerousPackages(context).contains(packageName)

    /**
     * Prefer the app's normal launcher entry so Messages opens its conversation list and
     * Phone opens its main tabs. Narrow dial/compose intents are only a fallback for system
     * Phone/Messages apps without a launcher activity: SENDTO would land straight on the
     * compose page, hiding existing messages and making Back leave the app.
     */
    fun launch(context: Context, packageName: String): Boolean {
        if (!isSelectable(context, packageName)) return false
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: when (packageName) {
                resolvePackage(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) ->
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).setPackage(packageName)
                resolvePackage(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) ->
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).setPackage(packageName)
                else -> return false
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeForDevice(context: Context, packages: List<String>): List<String> =
        sanitizeSelection(packages, context.packageName, dangerousPackages(context))
            .filter { isInstalled(context, it) }

    private fun resolveDefaultCommunicationApps(context: Context): List<String> {
        val result = mutableListOf<String>()

        fun addResolved(intent: Intent) {
            val pkg = resolvePackage(context, intent)
            if (pkg != null && isSelectable(context, pkg) && isInstalled(context, pkg) && !result.contains(pkg)) {
                result += pkg
            }
        }

        addResolved(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        addResolved(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        return result.take(MAX_APPS)
    }

    private fun resolvePackage(context: Context, intent: Intent): String? = try {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun getDangerousPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val packages = linkedSetOf(
            "com.android.settings",
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.documentsui"
        )

        // Home/launcher apps are never selectable: otherwise Home becomes an escape path.
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, 0).forEach { ri ->
                ri.activityInfo?.packageName?.let(packages::add)
            }
        } catch (_: Exception) {
        }

        // Default browser is excluded as an escape surface
        try {
            val defaultBrowser = pm.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            if (defaultBrowser != null && defaultBrowser != "android") {
                packages.add(defaultBrowser)
            }
        } catch (_: Exception) {
        }

        return packages
    }

    fun isBrowserPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p == "com.android.chrome" || p == "com.chrome.beta" || p == "com.chrome.canary" ||
            p == "org.mozilla.firefox" || p == "com.microsoft.emmx" || p == "com.opera.browser" ||
            p == "com.brave.browser" || p == "mark.via" || p == "com.kiwibrowser.browser" ||
            p.contains(".browser") || p.endsWith(".browser") || p.contains("ucbrowser") ||
            p.contains("quark")
    }

    fun isSelectable(context: Context, packageName: String, cachedDangerous: Set<String>? = null): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        if (isBrowserPackage(packageName)) return false
        val dangerous = cachedDangerous ?: getDangerousPackages(context)
        return !dangerous.contains(packageName)
    }

    private fun dangerousPackages(context: Context): Set<String> = getDangerousPackages(context)
}
