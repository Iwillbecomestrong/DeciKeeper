/**
 * =================================================================================
 * [Context Header]
 * * @file: FeedbackContactContractTest.kt
 * * @role: Test
 * * @description: 问题反馈与联系信息契约测试。
 * * @responsibilities:
 * 1. 验证 LogExporter.DEFAULT_RECIPIENT 为正式开发者邮箱。
 * 2. 验证 contact_and_complaint.txt 包含开发者邮箱、官网暂无说明、GitHub/Gitee Issue 推荐渠道与 4 要素范式。
 * 3. 验证 privacy_policy.txt 包含开发者联系邮箱。
 * * @constraints (必读):
 * 1. 不依赖 Android Context，纯 JVM 测试。
 * =================================================================================
 */
package com.decikeeper.app.governance

import com.decikeeper.app.data.LogExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedbackContactContractTest {

    private fun getRawFile(filename: String): File {
        val candidate1 = File("src/main/res/raw/$filename")
        if (candidate1.exists()) return candidate1
        val candidate2 = File("app/src/main/res/raw/$filename")
        if (candidate2.exists()) return candidate2
        error("Cannot find $filename (cwd=${File(".").absolutePath})")
    }

    @Test
    fun logExporterDefaultRecipientIsConfiguredToDeveloperEmail() {
        assertEquals(
            "LogExporter.DEFAULT_RECIPIENT 必须配置为开发者真实邮箱",
            "cyz200303@163.com",
            LogExporter.DEFAULT_RECIPIENT
        )
    }

    @Test
    fun contactAndComplaintRawFileHasDeveloperEmailAndNoWebsitePlaceholder() {
        val file = getRawFile("contact_and_complaint.txt")
        val content = file.readText()

        assertTrue(
            "contact_and_complaint.txt 必须包含联系邮箱 cyz200303@163.com",
            content.contains("cyz200303@163.com")
        )
        assertTrue(
            "contact_and_complaint.txt 必须包含 GitHub 官网链接",
            content.contains("https://github.com/Iwillbecomestrong/DeciKeeper")
        )
        assertTrue(
            "contact_and_complaint.txt 必须包含 Gitee 官网链接",
            content.contains("https://gitee.com/my_room_welcome_you/decikeeper")
        )
        assertFalse(
            "contact_and_complaint.txt 不得遗留联系邮箱待法务填写占位符",
            content.contains("联系邮箱：[待法务填写]")
        )
        assertFalse(
            "contact_and_complaint.txt 不得遗留官方网站待法务填写占位符",
            content.contains("官方网站：[待法务填写]")
        )
        assertTrue(
            "contact_and_complaint.txt 应包含 GitHub Issues 链接",
            content.contains("https://github.com/Iwillbecomestrong/DeciKeeper/issues")
        )
        assertTrue(
            "contact_and_complaint.txt 应包含 Gitee Issues 链接",
            content.contains("https://gitee.com/my_room_welcome_you/decikeeper/issues")
        )
    }

    @Test
    fun privacyPolicyRawFileHasDeveloperEmail() {
        val file = getRawFile("privacy_policy.txt")
        val content = file.readText()

        assertTrue(
            "privacy_policy.txt 必须包含联系邮箱 cyz200303@163.com",
            content.contains("cyz200303@163.com")
        )
        assertFalse(
            "privacy_policy.txt 不得遗留常规联系邮箱待法务填写占位符",
            content.contains("- 联系邮箱：[待法务填写]")
        )
    }
}
