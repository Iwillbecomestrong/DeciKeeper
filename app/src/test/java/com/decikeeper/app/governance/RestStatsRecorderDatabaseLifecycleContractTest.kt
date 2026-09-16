package com.decikeeper.app.governance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestStatsRecorderDatabaseLifecycleContractTest {

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    @Test
    fun restStatsRecorder_managesDatabaseViaWithDatabase() {
        val recorderSource = source("src/main/java/com/decikeeper/app/manager/statistics/RestStatsRecorder.kt")

        assertTrue("Must declare withDatabase helper", recorderSource.contains("inline fun <T> withDatabase("))
        assertTrue("withDatabase must ensure database.close() in finally", recorderSource.contains("database.close()"))

        // Forbid dangling direct calls on unclosed database(context) instances
        assertFalse("Must not call database(context).delete directly", recorderSource.contains("database(context).delete"))
        assertFalse("Must not call database(context).update directly", recorderSource.contains("database(context).update"))
        assertFalse("Must not call database(context).clear directly", recorderSource.contains("database(context).clear"))
        assertFalse("Must not call database(context).replaceAll directly", recorderSource.contains("database(context).replaceAll"))
    }

    @Test
    fun restStatsDatabase_clear_holdsSingleDatabaseInstance() {
        val dbSource = source("src/main/java/com/decikeeper/app/manager/statistics/RestStatsDatabase.kt")
        val clearMethod = dbSource.substringAfter("fun clear()").substringBefore("private fun isMigrationDone")

        // fun clear() must hold a single val db = writableDatabase
        assertTrue("clear() must declare val db = writableDatabase", clearMethod.contains("val db = writableDatabase"))
        assertFalse(
            "clear() must not repeatedly invoke writableDatabase property in transaction",
            clearMethod.contains("writableDatabase.delete") || clearMethod.contains("writableDatabase.beginTransaction")
        )
    }
}
