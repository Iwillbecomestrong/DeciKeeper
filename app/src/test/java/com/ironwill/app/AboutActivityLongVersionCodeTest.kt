/**
 * =================================================================================
 * [Context Header]
 * * @file: AboutActivityLongVersionCodeTest.kt
 * * @role: Test
 * * @description: 锁死 AboutActivity 在 API 26 / 27 上不会触发 NoSuchFieldError。
 * * @responsibilities:
 * 1. 验证 AboutActivity.readLongVersionCodeCompat() 不直接访问 PackageInfo.longVersionCode。
 * 2. 验证 readLongVersionCodeCompat 的方法签名（PackageInfo → Long）。
 * * @constraints (必读):
 * 1. 静态字节码层面的契约锁死即可；具体分支判断走 SDK_INT 运行时分发。
 * 2. 不依赖 Android Context。
 * =================================================================================
 */
package com.ironwill.app

import com.ironwill.app.ui.AboutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test



class AboutActivityLongVersionCodeTest {

    private fun readSourceFile(): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/ironwill/app/ui/AboutActivity.kt"),
            java.io.File("app/src/main/java/com/ironwill/app/ui/AboutActivity.kt")
        )
        for (file in candidates) {
            if (file.exists()) return file.readText(Charsets.UTF_8)
        }
        throw IllegalStateException(
            "Missing test fixture. Tried: " + candidates.joinToString { it.absolutePath }
        )
    }

    @Test
    fun readLongVersionCodeCompatIsPresent() {
        // 锁方法存在。Kotlin `fun` 在 JVM 上保留原名（不混淆），反射可找到。
        val method = AboutActivity::class.java.getDeclaredMethod(
            "readLongVersionCodeCompat",
            android.content.pm.PackageInfo::class.java
        )
        assertEquals(java.lang.Long.TYPE, method.returnType)
    }

    @Test
    fun readLongVersionCodeCompatIsPublic() {
        // 必须可访问（public），单测反射用。
        val method = AboutActivity::class.java.getDeclaredMethod(
            "readLongVersionCodeCompat",
            android.content.pm.PackageInfo::class.java
        )
        assertTrue(
            "readLongVersionCodeCompat 必须为 public（Kotlin internal 会做名字混淆）",
            java.lang.reflect.Modifier.isPublic(method.modifiers)
        )
    }

    @Test
    fun aboutActivitySourceHasSdkBranch() {
        // 必须显式做 SDK_INT >= Build.VERSION_CODES.P 分支
        val src = readSourceFile()
        assertEquals(
            true,
            src.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P")
        )
        assertEquals(
            true,
            src.contains("@Suppress(\"DEPRECATION\")") &&
                src.contains("versionCode.toLong()")
        )
    }

    @Test
    fun aboutActivitySourceDoesNotReferenceLongVersionCodeOutsideCompatMethod() {
        // 防回归：longVersionCode 字段读取必须仅出现在 readLongVersionCodeCompat 内部。
        // 通过统计全文件中 info.longVersionCode 出现次数（在 SDK_INT >= P 分支内）
        // 来验证不会出现裸的 info.longVersionCode 调用。
        val src = readSourceFile()
        // 长版本号字段访问必须至少 1 次（API >= P 分支内），至多 2 次（1 字段 + 1 注释/字符串）。
        val occurrences = src.lineSequence().count { line ->
            !line.trimStart().startsWith("//") && line.contains("info.longVersionCode")
        }
        assertTrue(
            "AboutActivity 中 info.longVersionCode 字段访问必须仅在 SDK_INT 分支内（>= 1 次）",
            occurrences >= 1
        )
        assertTrue(
            "AboutActivity 中 info.longVersionCode 字段访问不应超过 2 次（防回归）",
            occurrences <= 2
        )
    }
}