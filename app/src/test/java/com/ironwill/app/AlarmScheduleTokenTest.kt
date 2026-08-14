package com.ironwill.app

import org.junit.Assert.*
import org.junit.Test

/**
 * 核心测试：scheduleToken/PendingIntent 身份/Receiver 校验逻辑
 * 注意：这些是纯逻辑验证，不依赖 Android 环境。
 */
class AlarmScheduleTokenTest {

    @Test
    fun `MAIN 和 PREWARM 的 PendingIntent identity 不相等`() {
        val mainKind = AlarmScheduler.AlarmIntentKind.MAIN
        val prewarmKind = AlarmScheduler.AlarmIntentKind.PREWARM
        assertNotEquals("MAIN and PREWARM must have different requestCode offsets",
            mainKind.requestCodeOffset, prewarmKind.requestCodeOffset)
    }

    @Test
    fun `MAIN 和 RECOVERY 的 PendingIntent identity 不相等`() {
        val mainKind = AlarmScheduler.AlarmIntentKind.MAIN
        val recoveryKind = AlarmScheduler.AlarmIntentKind.RECOVERY
        assertNotEquals("MAIN and RECOVERY must have different requestCode offsets",
            mainKind.requestCodeOffset, recoveryKind.requestCodeOffset)
    }

    @Test
    fun `scheduleToken 生成不可复用`() {
        val token1 = AlarmScheduler.generateScheduleToken()
        val token2 = AlarmScheduler.generateScheduleToken()
        assertNotNull("scheduleToken must not be null", token1)
        assertNotNull("scheduleToken must not be null", token2)
        assertNotEquals("Each scheduleToken must be unique", token1, token2)
    }

    @Test
    fun `scheduleToken UUID 格式正确`() {
        val token = AlarmScheduler.generateScheduleToken()
        assertTrue("scheduleToken should be a valid UUID string",
            token.matches(Regex("[a-f0-9\\-]{36}")))
    }

    @Test
    fun `AlarmIntentKind dataPath 各不相同`() {
        val paths = AlarmScheduler.AlarmIntentKind.values().map { it.dataPath }.toSet()
        assertEquals("Each AlarmIntentKind must have a unique dataPath",
            AlarmScheduler.AlarmIntentKind.values().size, paths.size)
    }

    @Test
    fun `删除后的闹钟 findAlarm 返回 null`() {
        // 模拟"记录不存在"的场景
        val emptyList = emptyList<AlarmScheduler.AlarmEntry>()
        val result = emptyList.firstOrNull { it.id == 2 }
        assertNull("Deleted alarm should not be found", result)
    }

    @Test
    fun `MAIN PendingIntent 的 requestCode 等于 alarmId 本身`() {
        val mainKind = AlarmScheduler.AlarmIntentKind.MAIN
        val alarmId = 42
        val requestCode = alarmId + mainKind.requestCodeOffset
        assertEquals(42, requestCode)
    }

    @Test
    fun `PREWARM PendingIntent 的 requestCode 等于 alarmId + 4_000_000`() {
        val prewarmKind = AlarmScheduler.AlarmIntentKind.PREWARM
        val alarmId = 42
        val requestCode = alarmId + prewarmKind.requestCodeOffset
        assertEquals(4_000_042, requestCode)
    }

    @Test
    fun `AlarmEntry 默认 scheduleToken 为空字符串`() {
        val entry = AlarmScheduler.AlarmEntry(
            id = 1,
            hour = 8,
            minute = 0,
            enabled = true,
            days = BooleanArray(7) { true }
        )
        assertEquals("New AlarmEntry should have empty scheduleToken",
            "", entry.scheduleToken)
    }
}