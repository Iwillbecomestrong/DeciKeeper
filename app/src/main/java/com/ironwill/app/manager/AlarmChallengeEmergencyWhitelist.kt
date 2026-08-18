package com.ironwill.app.manager

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Global emergency-app allowlist used only while an alarm challenge is active.
 * The challenge itself remains active; this merely allows up to two explicitly
 * configured apps to be temporarily reachable.
 */
object AlarmChallengeEmergencyWhitelist {
    private const val PREFS = "alarm_challenge_emergency_whitelist"
    private const val KEY_PACKAGES = "packages"
    const val MAX_APPS = 2

    /** Pure policy seam used by JVM tests and foreground-enforcement code. */
    internal fun isPackageAllowedDuringChallenge(
        alarmActive: Boolean,
        foregroundPackage: String,
        selfPackage: String,
        allowedPackages: Set<String>
    ): Boolean {
        if (!alarmActive) return true
        return foregroundPackage == selfPackage || allowedPackages.contains(foregroundPackage)
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
        val stored = prefs.getStringSet(KEY_PACKAGES, null)
        if (stored != null) return sanitizeForDevice(context, stored.toList())

        val defaults = resolveDefaultCommunicationApps(context)
        if (defaults.isNotEmpty()) savePackages(context, defaults)
        return defaults
    }

    fun savePackages(context: Context, packages: Collection<String>) {
        val sanitized = sanitizeForDevice(context, packages.toList())
        // LinkedHashSet semantics are not guaranteed by SharedPreferences, so callers
        // must not rely on display order after process restart.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, sanitized.toSet())
            .apply()
    }

    fun isAllowed(context: Context, packageName: String, alarmActive: Boolean): Boolean =
        isPackageAllowedDuringChallenge(
            alarmActive = alarmActive,
            foregroundPackage = packageName,
            selfPackage = context.packageName,
            allowedPackages = getPackages(context).toSet()
        )

    fun isSelectable(context: Context, packageName: String): Boolean =
        packageName.isNotBlank() &&
            packageName != context.packageName &&
            !dangerousPackages(context).contains(packageName)

    fun launch(context: Context, packageName: String): Boolean {
        if (!isSelectable(context, packageName)) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
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
        val pm = context.packageManager
        val result = mutableListOf<String>()

        fun resolve(intent: Intent) {
            val pkg = try { pm.resolveActivity(intent, 0)?.activityInfo?.packageName } catch (_: Exception) { null }
            if (pkg != null && isSelectable(context, pkg) && isInstalled(context, pkg) && !result.contains(pkg)) {
                result += pkg
            }
        }

        // Default phone app.
        resolve(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        // Default SMS/messages app.
        resolve(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))

        return result.take(MAX_APPS)
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun dangerousPackages(context: Context): Set<String> {
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

        // Browsers are also excluded because they are general-purpose escape surfaces.
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            pm.queryIntentActivities(webIntent, 0).forEach { ri ->
                ri.activityInfo?.packageName?.let(packages::add)
            }
        } catch (_: Exception) {
        }

        return packages
    }
}
