package com.ironwill.app

import com.ironwill.app.data.SegmentAdverseAnalysis
import com.ironwill.app.data.TimeSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config




/**
 * [BUG 回归] 用户报告（2026-08-14）：在全局黑名单基础上往时段名单添加应用，
 * 被误判为"延迟生效"。
 *
 * 根因：名单页只展示有启动入口(hasLaunchIntent)的应用；全局黑名单含无入口/已卸载
 * 的包时（全选/反选后必然从选择中丢失），classifyListAppsAdverse 以完整全局黑名单
 * 为基准做 containsAll → 误判"减少=放松=次日生效"。
 *
 * 修复：基准改为"可展示子集"（生产=PackageManager.hasLaunchIntent 过滤，见
 * TimeRuleManager.classifyListAppsAdverse；本测试用显式 resolver 验证判定核心
 * classifyListAppsAdverseCore）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentListAddOnGlobalReproTest {

    private fun makeSegment(listApps: List<String>?): TimeSegment = TimeSegment(
        startHour = 9, startMinute = 0,
        endHour = 12, endMinute = 0,
        limitMins = 30,
        name = "上午",
        daysOfWeek = null,
        isEnabled = true,
        listApps = listApps
    )

    private val alpha = "com.example.alpha"
    private val beta = "com.example.beta"
    private val gamma = "com.example.gamma"
    private val stale = "com.removed.stale"

    @Test
    fun addOnTopOfGlobal_withHiddenStaleApp_shouldBeImmediate() {
        // 全局黑名单 = {alpha, beta, stale}；stale 无启动入口，名单页不可勾选。
        // 用户又勾选了 gamma，保存 → 纯加严，应立即生效。
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf(alpha, beta, gamma)),
            oldEffective = setOf(alpha, beta, stale),
            selectable = { eff -> eff.filter { it.startsWith("com.example.") }.toSet() }
        )
        assertFalse("在全局基础上添加应用应=加严=立即生效，不得误判延迟", adverse)
    }

    @Test
    fun selectAllOnFollowMode_shouldBeImmediate() {
        // 全选：勾选全部可展示应用；隐藏的 stale 包从选择中丢失，但不算"被移除"。
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf(alpha, gamma)),
            oldEffective = setOf(alpha, stale),
            selectable = { eff -> eff.filter { it.startsWith("com.example.") }.toSet() }
        )
        assertFalse("跟随态全选应=加严=立即生效", adverse)
    }

    @Test
    fun genuinelyRemovingDisplayedApp_isStillAdverse() {
        // 对照：真实移除一个"可展示"的全局应用 → 仍应判定为不利（延迟）——修复不能过度放松
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf(alpha)),
            oldEffective = setOf(alpha, beta),
            selectable = { it } // beta 可展示且被移除
        )
        assertTrue("真实移除可展示应用仍是放松 → 不利", adverse)
    }
}
