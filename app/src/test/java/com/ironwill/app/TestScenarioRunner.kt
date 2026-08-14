/**
 * =================================================================================
 * [Context Header]
 * * @file: TestScenarioRunner.kt
 * * @role: Test
 * * @description: Safe full-suite test entry used by Home long-press.
 * * @responsibilities:
 * 1. Run the comprehensive suite in an isolated context.
 * 2. Export the result text for sharing.
 * * @context:
 * - upstream: HomeFragment long-press action
 * - downstream: TestScenarioManager (isolated wrapper context)
 * * @constraints (must read):
 * 1. Never write to real user SharedPreferences from this entry.
 * 2. Always clear global caches after the isolated run.
 * 3. Keep result export UTF-8.
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream

object TestScenarioRunner {

    private const val RESULT_FILE_NAME = "SafeTestResult.txt"

    fun runAllTests(context: Context): String {
        val namespace = "safe_runner_" + System.currentTimeMillis()
        val isolatedContext = IsolatedTestContext(context.applicationContext, namespace)
        val appContext = context.applicationContext

        val fullSuiteResult = try {
            // Ensure disk-log assertions use the same isolated filesDir.
            LogExporter.init(isolatedContext)
            TestScenarioManager.runAllTests(isolatedContext)
        } finally {
            // Restore default logger context for normal app runtime.
            LogExporter.init(appContext)
            // Clear in-memory caches that may have been touched during isolated run.
            TimeRuleManager.invalidateCache()
            AppWindowManager.invalidateCache()
            clearCreditManagerCacheByReflection()
        }

        val result = buildString {
            append("==========================================\n")
            append("IronWill Safe Full Suite\n")
            append("Mode: Isolated Context\n")
            append("Namespace: ").append(namespace).append('\n')
            append("==========================================\n\n")
            append(fullSuiteResult)
        }

        saveResultToFile(context, result)
        return result
    }

    fun getLatestResultFile(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalFile = File(externalDir, RESULT_FILE_NAME)
            if (externalFile.exists()) return externalFile
        }
        val internalFile = File(context.filesDir, RESULT_FILE_NAME)
        return if (internalFile.exists()) internalFile else null
    }

    private fun saveResultToFile(context: Context, content: String) {
        try {
            val internalFile = File(context.filesDir, RESULT_FILE_NAME)
            FileOutputStream(internalFile).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }

            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val externalFile = File(externalDir, RESULT_FILE_NAME)
                FileOutputStream(externalFile).use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            // Keep test runner non-fatal for export failures.
        }
    }

    private fun clearCreditManagerCacheByReflection() {
        runCatching {
            val field = CreditManager::class.java.getDeclaredField("cachedBlacklist")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    private class IsolatedTestContext(
        base: Context,
        private val namespace: String
    ) : ContextWrapper(base) {

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return baseContext.getSharedPreferences("${namespace}__$name", mode)
        }

        override fun createDeviceProtectedStorageContext(): Context {
            val dp = baseContext.createDeviceProtectedStorageContext()
            return if (dp == null || dp === baseContext) this else IsolatedTestContext(dp, namespace)
        }

        override fun getFilesDir(): File {
            val dir = File(baseContext.filesDir, "safe_test/$namespace")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        override fun getNoBackupFilesDir(): File {
            val dir = File(baseContext.noBackupFilesDir, "safe_test/$namespace")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        override fun getCacheDir(): File {
            val dir = File(baseContext.cacheDir, "safe_test/$namespace")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        override fun getExternalFilesDir(type: String?): File? {
            val baseExternal = baseContext.getExternalFilesDir(type) ?: return null
            val dir = File(baseExternal, "safe_test/$namespace")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }
}
