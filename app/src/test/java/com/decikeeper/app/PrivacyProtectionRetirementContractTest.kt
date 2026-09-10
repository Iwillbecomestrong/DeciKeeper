/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacyProtectionRetirementContractTest.kt
 * * @role: Test
 * * @description: TASK-03 第一套死代码清退的硬门禁契约。
 * 验证目标:
 * - 第一套全限定引用 在 app/src/ 为空 (硬门禁 1)
 * - 第一套 Kotlin 源文件 protection/PrivacyProtection.kt 不存在
 * - 第一套独占字符串资源 privacy_protection_title/message 不存在
 * - TestScenarioManager 模块23第一套关键词完整性检查片段不存在
 * - TestScenarioManager 模块29第一套逻辑镜像方法不存在
 * - Overview.md / PROJECT.md / Requirements.md / lint-baseline.xml 不再指向第一套
 * - 第二套生产标识 (shouldBlockPrivateSpaceAddApp / PRIVATE_SPACE_ADD_APP / refreshPrivateSpaceSession) 仍存在
 * - PrivacyProtectionManager (object) 与 isEnforcing() 仍存在
 * * @context:
 * - 上游: Gradle Unit Test (JVM only, 不依赖 Android runtime)
 * - 下游: 文件系统与源码文本的硬门禁
 * * @constraints (必读):
 * 1. 不复制检测算法 - 通过文件存在/不存在与关键字定位来约束
 * 2. 不依赖真实设备, 不执行 adb install / uninstall
 * =================================================================================
 */
package com.decikeeper.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * TASK-03 退休契约。
 *
 * 关键设计:
 * - 通过 java.io.File 直接读取源码树, 不绕开 Gradle
 * - 路径以项目根 (.) 为基准, 与 Android 模块相对路径对齐
 * - 每条断言都对应 PLAN §A1 验证策略中的一条硬门禁
 */
class PrivacyProtectionRetirementContractTest {

    private val projectRoot: File by lazy {
        // 当前工作目录是 module 根 (app/), 需向上找项目根
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Cannot locate project root (settings.gradle not found)" }
        dir
    }

    private val mainSrcDir: File
        get() = File(projectRoot, "app/src/main")

    private fun read(file: File): String =
        if (file.exists()) file.readText(Charsets.UTF_8) else ""

    private fun readMain(relative: String): String =
        read(File(mainSrcDir, relative))

    // =========================================================================
    // 第一组: 第一套全限定引用 (硬门禁 1)
    // =========================================================================
    @Test
    fun firstSuite_fullyQualifiedReference_isAbsent() {
        val pattern = Regex("""com\.ironwill\.app\.protection\.PrivacyProtection\b""")
        val hits = mutableListOf<String>()
        File(mainSrcDir, "java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { f ->
                pattern.findAll(read(f)).forEach { m ->
                    hits.add("${f.relativeTo(projectRoot).path}:${m.range.first}")
                }
            }
        assertTrue(
            "First-suite fully qualified reference must be empty, found: $hits",
            hits.isEmpty()
        )
    }

    // =========================================================================
    // 第二组: 第一套 Kotlin 源文件不存在 (硬门禁 2)
    // =========================================================================
    @Test
    fun firstSuite_kotlinSource_doesNotExist() {
        val f = File(mainSrcDir, "java/com/decikeeper/app/protection/PrivacyProtection.kt")
        assertFalse(
            "First-suite file must be deleted: ${f.absolutePath}",
            f.exists()
        )
    }

    // =========================================================================
    // 第三组: 第一套独占字符串资源不存在 (硬门禁 3)
    // =========================================================================
    @Test
    fun firstSuite_stringResources_areAbsent() {
        val strings = readMain("res/values/strings.xml")
        assertFalse(
            "privacy_protection_title must be removed",
            strings.contains("privacy_protection_title")
        )
        assertFalse(
            "privacy_protection_message must be removed",
            strings.contains("privacy_protection_message")
        )
    }

    // =========================================================================
    // 第四组: TestScenarioManager 模块23第一套关键词完整性检查不存在 (硬门禁 4)
    // =========================================================================
    @Test
    fun testScenarioManager_module23_firstSuiteKeywordIntegrity_isAbsent() {
        val src = readMain("java/com/decikeeper/app/TestScenarioManager.kt")
        assertFalse(
            "Module23 keyword integrity check (SEARCH_HISTORY_KEYWORDS mirror) must be removed",
            src.contains("PrivacyProtection.SEARCH_HISTORY_KEYWORDS") ||
                src.contains("PrivacyProtection.EXPLICIT_SEARCH_KEYWORDS")
        )
    }

    // =========================================================================
    // 第五组: TestScenarioManager 模块29第一套逻辑镜像方法不存在 (硬门禁 5)
    // =========================================================================
    @Test
    fun testScenarioManager_module29_firstSuiteLogicMirror_isAbsent() {
        val src = readMain("java/com/decikeeper/app/TestScenarioManager.kt")
        assertFalse(
            "Module29 first-suite logic mirror method must be removed",
            src.contains("runPrivacyProtectionLogicVerification")
        )
        assertFalse(
            "Module29 mirror of PrivacyProtection.PRIVACY_KEYWORDS must be removed",
            src.contains("PrivacyProtection.PRIVACY_KEYWORDS")
        )
        assertFalse(
            "Module29 mirror of PrivacyProtection.VERIFICATION_KEYWORDS must be removed",
            src.contains("PrivacyProtection.VERIFICATION_KEYWORDS")
        )
    }

    // =========================================================================
    // 第六组: 项目索引/概览/需求/lint baseline 不再指向第一套 (硬门禁 6)
    // =========================================================================
    @Test
    fun projectIndex_documents_firstSuiteReferences_removed() {
        val overview = readMain("java/com/decikeeper/app/Overview.md")
        assertFalse(
            "Overview.md must drop protection/PrivacyProtection entry",
            overview.contains("protection/PrivacyProtection")
        )

        val project = read(File(projectRoot, "PROJECT.md"))
        assertFalse(
            "PROJECT.md must drop protection/PrivacyProtection pointer",
            project.contains("protection/PrivacyProtection")
        )

        val requirements = read(File(projectRoot, "Requirements.md"))
        assertFalse(
            "Requirements.md must drop PrivacyProtection.kt from 涉及文件",
            requirements.contains("PrivacyProtection.kt")
        )

        val lintBaseline = read(File(projectRoot, "app/lint-baseline.xml"))
        assertFalse(
            "lint-baseline.xml must drop privacy_protection_title orphan entry",
            lintBaseline.contains("privacy_protection_title")
        )
        assertFalse(
            "lint-baseline.xml must drop privacy_protection_message orphan entry",
            lintBaseline.contains("privacy_protection_message")
        )
    }

    // =========================================================================
    // 第七组: 第二套生产标识仍存活 (反向硬门禁)
    // =========================================================================
    @Test
    fun secondSuite_productionIdentifiers_alive() {
        val antiUninstall = readMain("java/com/decikeeper/app/protection/AntiUninstallProtection.kt")
        assertTrue(
            "shouldBlockPrivateSpaceAddApp must still exist in second suite",
            antiUninstall.contains("shouldBlockPrivateSpaceAddApp")
        )
        assertTrue(
            "PRIVATE_SPACE_ADD_APP log reason must still exist in second suite",
            antiUninstall.contains("PRIVATE_SPACE_ADD_APP")
        )
        assertTrue(
            "refreshPrivateSpaceSession must still exist in second suite",
            antiUninstall.contains("refreshPrivateSpaceSession")
        )
    }

    // =========================================================================
    // 第八组: PrivacyProtectionManager (object) 与 isEnforcing() 仍存活 (反向硬门禁)
    // =========================================================================
    @Test
    fun privacyProtectionManager_and_isEnforcing_alive() {
        val manager = readMain("java/com/decikeeper/app/manager/PrivacyProtectionManager.kt")
        assertTrue(
            "PrivacyProtectionManager object must still exist",
            manager.contains("object PrivacyProtectionManager")
        )
        assertTrue(
            "PrivacyProtectionManager.isEnforcing() must still exist",
            manager.contains("fun isEnforcing")
        )
    }
}
