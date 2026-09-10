package com.decikeeper.app

import com.decikeeper.app.manager.PackageForegroundEventExtractor
import com.decikeeper.app.manager.RawUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageForegroundEventExtractorTest {

    @Test
    fun eventsAreStrictlyIsolatedBetweenPackages() {
        // 模拟交错事件：
        // 1. 抖音在 1000ms 触发 MOVE_TO_FOREGROUND
        // 2. 微信在 1500ms 触发 MOVE_TO_BACKGROUND
        // 3. 桌面在 1600ms 触发 ACTIVITY_RESUMED
        val events = listOf(
            RawUsageEvent(
                packageName = "com.ss.android.ugc.aweme",
                eventType = PackageForegroundEventExtractor.MOVE_TO_FG,
                timeStamp = 1000L
            ),
            RawUsageEvent(
                packageName = "com.tencent.mm",
                eventType = PackageForegroundEventExtractor.MOVE_TO_BG,
                timeStamp = 1500L
            ),
            RawUsageEvent(
                packageName = "com.bbk.launcher2",
                eventType = PackageForegroundEventExtractor.ACTIVITY_RESUMED,
                timeStamp = 1600L
            )
        )

        // 提取抖音事件：证明微信的 BG 和桌面的 RESUMED 绝不会污染抖音的时间戳
        val douyinEvents = PackageForegroundEventExtractor.extract(events, "com.ss.android.ugc.aweme")
        assertEquals(1000L, douyinEvents.lastForegroundAtMs)
        assertEquals(0L, douyinEvents.lastBackgroundAtMs)

        // 提取微信事件：证明只有微信自身的 BG 事件
        val wechatEvents = PackageForegroundEventExtractor.extract(events, "com.tencent.mm")
        assertEquals(0L, wechatEvents.lastForegroundAtMs)
        assertEquals(1500L, wechatEvents.lastBackgroundAtMs)

        // 提取桌面事件：证明只有桌面自身的 RESUMED 事件
        val launcherEvents = PackageForegroundEventExtractor.extract(events, "com.bbk.launcher2")
        assertEquals(1600L, launcherEvents.lastForegroundAtMs)
        assertEquals(0L, launcherEvents.lastBackgroundAtMs)
    }

    @Test
    fun samePackageMultipleEventsKeepsLatestTimestamps() {
        // 同一个包多次切前台/后台
        val events = listOf(
            RawUsageEvent("com.ss.android.ugc.aweme", PackageForegroundEventExtractor.MOVE_TO_FG, 1000L),
            RawUsageEvent("com.ss.android.ugc.aweme", PackageForegroundEventExtractor.ACTIVITY_PAUSED, 1200L),
            RawUsageEvent("com.ss.android.ugc.aweme", PackageForegroundEventExtractor.ACTIVITY_RESUMED, 2000L)
        )
        val extracted = PackageForegroundEventExtractor.extract(events, "com.ss.android.ugc.aweme")
        assertEquals(2000L, extracted.lastForegroundAtMs)
        assertEquals(1200L, extracted.lastBackgroundAtMs)
    }

    @Test
    fun unknownPackageReturnsZeroTimestamps() {
        val events = listOf(
            RawUsageEvent("com.ss.android.ugc.aweme", PackageForegroundEventExtractor.MOVE_TO_FG, 1000L)
        )
        val extracted = PackageForegroundEventExtractor.extract(events, "com.unknown.app")
        assertEquals(0L, extracted.lastForegroundAtMs)
        assertEquals(0L, extracted.lastBackgroundAtMs)
    }

    @Test
    fun activityStoppedIsExtractedAsBackgroundEvent() {
        // Vivo 小窗关闭时只发出 ACTIVITY_STOPPED (23)，必须被记录为 lastBackgroundAtMs
        val events = listOf(
            RawUsageEvent("com.tencent.mm", PackageForegroundEventExtractor.MOVE_TO_FG, 1000L),
            RawUsageEvent("com.tencent.mm", PackageForegroundEventExtractor.ACTIVITY_STOPPED, 1500L)
        )
        val extracted = PackageForegroundEventExtractor.extract(events, "com.tencent.mm")
        assertEquals(1000L, extracted.lastForegroundAtMs)
        assertEquals(1500L, extracted.lastBackgroundAtMs)
    }
}
