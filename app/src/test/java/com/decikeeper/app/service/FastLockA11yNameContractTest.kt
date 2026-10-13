package com.decikeeper.app.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：FastLockService 曾自行拼全类名去 contains 系统无障碍设置，vivo 存短格式时误判"已关闭"，
 * 把缓存改 false 并在 0.3s 内 stopSelf，遮罩瞬间消失。判定必须统一走 PermissionManager。
 */
class FastLockA11yNameContractTest {
    private fun read(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(cwd, path), File(cwd, path.removePrefix("app/")), File(cwd.parentFile ?: cwd, path))
            .firstOrNull { it.isFile }?.readText() ?: error("Cannot locate $path")
    }

    @Test
    fun fastLockServiceMustNotHandRollA11yNameMatching() {
        val src = read("app/src/main/java/com/decikeeper/app/service/FastLockService.kt")
        assertFalse(src.contains("canonicalName"))
        assertTrue(src.contains("PermissionManager.isAccessibilityEnabled(this)"))
    }

    @Test
    fun fastLockLayoutDefaultCountdownMatchesMaxVisible() {
        val xml = read("app/src/main/res/layout/window_fast_lock.xml")
        assertTrue(xml.contains("剩余 10 秒"))
        assertFalse(xml.contains("剩余 30 秒"))
    }
}
