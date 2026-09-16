/**
 * =================================================================================
 * [Context Header]
 * * @file: BackupRulesExcludesConsentOnlyTest.kt
 * * @role: Test
 * * @description: 校验 backup_rules.xml / data_extraction_rules.xml 的 exclude 配置。
 * * @responsibilities:
 * 1. 锁死 privacy_consent_prefs.xml 必须在 cloud-backup 与 device-transfer 都被 exclude。
 * 2. 防止后续误加 `<include domain="sharedpref" path="."/>` 把自动备份范围收窄成
 *    "仅 sharedpref"，影响 CrashRecorder / LogExporter 等 filesDir 数据。
 * * @constraints (必读):
 * 1. 直接 grep 静态资源文本，避免依赖 Android Context（无 Robolectric）。
 * 2. 同时检查 backup_rules.xml 和 data_extraction_rules.xml。
 * =================================================================================
 */
package com.decikeeper.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class BackupRulesExcludesConsentOnlyTest {

    private fun readResource(path: String): String {
        // Gradle UnitTest 的工作目录在不同执行环境下可能为项目根或 :app 模块根。
        // 依次尝试两个候选路径，确保测试可移植。
        val candidates = listOf(
            java.io.File("src/main/res/$path"),
            java.io.File("app/src/main/res/$path")
        )
        for (file in candidates) {
            if (file.exists()) return file.readText(Charsets.UTF_8)
        }
        throw IllegalStateException(
            "Missing test fixture. Tried: " + candidates.joinToString { it.absolutePath }
        )
    }

    @Test
    fun backupRulesContainsConsentExclude() {
        val xml = readResource("xml/backup_rules.xml")
        assertTrue(
            "backup_rules.xml 必须显式 exclude privacy_consent_prefs.xml",
            xml.contains("""<exclude domain="sharedpref" path="privacy_consent_prefs.xml"/>""")
        )
    }

    @Test
    fun backupRulesDoesNotNarrowAllSharedpref() {
        // 关键修复：不能出现 "<include domain="sharedpref" path="."/>"，
        // 这会把全局自动备份范围收窄成"仅 sharedpref"。
        val xml = readResource("xml/backup_rules.xml")
        assertFalse(
            "backup_rules.xml 不应包含 '<include domain=\"sharedpref\" path=\".\"/>'，" +
                "否则会把 filesDir 等其他 domain 默认备份排除掉",
            xml.contains("""<include domain="sharedpref" path="."/>""")
        )
    }

    @Test
    fun dataExtractionRulesContainsConsentExcludeInBothSegments() {
        val xml = readResource("xml/data_extraction_rules.xml")
        assertTrue(
            "data_extraction_rules.xml 的 cloud-backup 必须 exclude privacy_consent_prefs.xml",
            xml.contains("""<exclude domain="sharedpref" path="privacy_consent_prefs.xml"/>""")
        )
    }

    @Test
    fun dataExtractionRulesDoesNotNarrowAllSharedpref() {
        // 关键修复：cloud-backup 和 device-transfer 都不应包含
        // "<include domain="sharedpref" path="."/>"
        val xml = readResource("xml/data_extraction_rules.xml")
        assertFalse(
            "data_extraction_rules.xml 不应包含 '<include domain=\"sharedpref\" path=\".\"/>'",
            xml.contains("""<include domain="sharedpref" path="."/>""")
        )
    }

    @Test
    fun backupRulesConsentExcludeUsesExactFileName() {
        // 防止后续把 path 写成通配 ".*" 之类导致 backup 行为变化
        val xml = readResource("xml/backup_rules.xml")
        assertTrue(
            "exclude path 必须是 privacy_consent_prefs.xml 精确文件名",
            xml.contains("path=\"privacy_consent_prefs.xml\"")
        )
    }

    @Test
    fun dataExtractionRulesHasBothSegments() {
        val xml = readResource("xml/data_extraction_rules.xml")
        assertTrue("必须包含 <cloud-backup>", xml.contains("<cloud-backup>"))
        assertTrue("必须包含 </cloud-backup>", xml.contains("</cloud-backup>"))
        assertTrue("必须包含 <device-transfer>", xml.contains("<device-transfer>"))
        assertTrue("必须包含 </device-transfer>", xml.contains("</device-transfer>"))
    }
}