package com.decikeeper.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置备份的“逐文件明确分类”守护：新增 prefs 文件必须在 [BackupPrefsClassification] 登记，
 * 否则这里失败，避免运行状态或权限标记被悄悄带进导入。
 */
class BackupPrefsClassificationTest {

    private fun mainSourceDir(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, "app/src/main/java") }
            .firstOrNull { it.isDirectory }
            ?: generateSequence(start) { it.parentFile }
                .map { File(it, "src/main/java") }
                .firstOrNull { it.isDirectory }
            ?: error("cannot find main source dir from $start")
    }

    private val literalPrefsPattern = Regex("""getSharedPreferences\(\s*"([A-Za-z0-9_.-]+)"""")
    private val constPrefsPattern =
        Regex("""\b(?:const\s+)?val\s+(?:PREFS(?:_NAME)?|PREF_NAME|[A-Z_]*PREFS[A-Z_]*)\s*(?::\s*String\s*)?=\s*"([A-Za-z0-9_.-]+)"""")

    private fun prefsNamesInSource(): Set<String> {
        val names = linkedSetOf<String>()
        mainSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            literalPrefsPattern.findAll(text).forEach { names += it.groupValues[1] }
            constPrefsPattern.findAll(text).forEach { names += it.groupValues[1] }
        }
        return names
    }

    @Test
    fun everyPrefsFileInSourceIsClassified() {
        val unclassified = prefsNamesInSource().filter { BackupPrefsClassification.decisionOf(it) == null }
        assertTrue(
            "这些 SharedPreferences 文件尚未在 BackupPrefsClassification 登记备份处理方式: $unclassified",
            unclassified.isEmpty()
        )
    }

    @Test
    fun importAndMergeFilesAreCoveredByBackupCategories() {
        val covered = SettingsBackupManager.allCategoryFileNames()
        BackupPrefsClassification.decisions
            .filterValues { it == BackupDecision.IMPORT || it == BackupDecision.MERGE }
            .keys
            .forEach { name ->
                assertTrue("$name 标为导入/合并，但没有任何备份分类拥有它", name in covered)
            }
    }

    @Test
    fun skippedFilesAreNotOwnedByAnyBackupCategory() {
        val covered = SettingsBackupManager.allCategoryFileNames()
        BackupPrefsClassification.decisions
            .filterValues { it != BackupDecision.IMPORT && it != BackupDecision.MERGE }
            .keys
            .forEach { name ->
                assertTrue("$name 标为不导入/待评审，却被备份分类拥有", name !in covered)
            }
    }

    @Test
    fun emergencyExitQuotaIsTheOnlyMergedFile() {
        val merged = BackupPrefsClassification.decisions.filterValues { it == BackupDecision.MERGE }.keys
        assertEquals(setOf(AlarmEmergencyExitStore.PREFS_NAME), merged)
    }
}
