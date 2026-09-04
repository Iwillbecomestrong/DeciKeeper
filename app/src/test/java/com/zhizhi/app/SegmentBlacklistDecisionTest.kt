package com.zhizhi.app

import android.content.Context
import com.zhizhi.app.data.SegmentBlacklist
import com.zhizhi.app.data.TimeSegment
import com.zhizhi.app.manager.AppWindowManager
import com.zhizhi.app.manager.CreditManager
import com.zhizhi.app.manager.TimeRuleManager
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config







/**
 * TASK-02 (segment-blacklist) RED — 硬剔除 + 有效名单判定 + 锁屏统一判定。
 *
 * 语义（SPEC §2 / §7，PLAN §1 固化接口）：
 *   - listApps == null  → 继承态：effectiveList == 全局黑名单 − 硬剔除。
 *   - listApps != null  → 脱钩态：effectiveList == 名单 − 硬剔除（全局黑名单增删无影响）。
 *   - 硬剔除永远优先：launcher / settings / IME / 电话(dialer) / 短信(mms/sms) /
 *     IronWill 自身，无论名单内容如何都不锁、不进 effectiveList。
 *   - 核心「取代语义」：仅在本时段名单、不在全局黑名单的应用（如 com.example.segonly）
 *     在脱钩时段必须命中（isInEffectiveList == true），即 MonitorService 主判定
 *     （1755 isBlacklisted 分支）会拦它；前台守卫与 isSafe/特权/自由时间逻辑不变。
 *   - 时间窗（SPEC §2.9）：脱钩时段里 managed 但不在名单的应用，时间窗闸门仍生效
 *     （生产 shouldBlock 的 segmentDecoupled && isManaged -> isAppWindowBlocked 分支）。
 *
 * 说明（TASK-02 调查结论）：
 *   MonitorService 208/252/289/3023 的 ForegroundVote.BLACKLIST 只是诊断投票
 *   （feed ForegroundReportOutcome.diagnosticRecommendation，且生产注释明确
 *   "must NEVER override the business NO_LOCK decision"），不驱动锁屏；
 *   1216/1656/1678 是 presence 维持/诊断字段；1579/2145 是前台检测取证/候选采纳
 *   （红线 11 禁止改动）。最终门闸是 1755 + 1850，本测试用 isInEffectiveList /
 *   shouldLock 两个生产入口锁死该语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentBlacklistDecisionTest {

    private lateinit var context: Context

    private val rulesPrefs get() =
        context.getSharedPreferences("iron_will_rules", Context.MODE_PRIVATE)
    private val ledgerPrefs get() =
        context.getSharedPreferences("iron_will_ledger", Context.MODE_PRIVATE)
    private val appWindowPrefs get() =
        context.getSharedPreferences("app_window_rules", Context.MODE_PRIVATE)

    // 普通应用包名：刻意避开 isLauncher("home"/"launcher") 与
    // isInputMethod("input"/"ime"/"key"/"sogou"/"baidu"/"iflytek"/"wetype") 的保守匹配。
    private val normalGame = "com.example.game"
    private val segOnly = "com.example.segonly"
    private val qq = "com.tencent.mobileqq"
    private val wechat = "com.tencent.mm"
    private val timed = "com.example.timed"

    // 硬剔除样例
    private val launcher = "com.android.launcher"
    private val settings = "com.android.settings"
    private val ime = "com.sohu.inputmethod.sogou"
    private val phone = "com.android.phone"
    private val contacts = "com.android.contacts"
    private val messaging = "com.google.android.apps.messaging"
    private val systemui = "com.android.systemui"
    private val vivoSystemUiPlugin = "com.vivo.systemuiplugin"
    private val vivoUpslide = "com.vivo.upslide" // vivo 系统导航（System navigation）
    private val traceur = "com.android.traceur"
    private val clock = "com.android.BBKClock"
    private val deskclock = "com.android.deskclock"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        appWindowPrefs.edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        CreditManager.invalidateCache()
        AppWindowManager.invalidateCache()
    }

    private fun writeSegments(segments: List<TimeSegment>) {
        val json = TimeRuleManager.segmentsToJson(segments)
        rulesPrefs.edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun writeBlacklist(apps: Set<String>) {
        ledgerPrefs.edit().putStringSet("blacklist_apps_list", apps).commit()
        CreditManager.invalidateCache()
    }

    /**
     * 覆盖整天的段（start=00:00, end=24:00）。endHour=24 使 end=1440 分钟，
     * isInTimeRange 的 normal 分支 current in [0,1440) 恒真 → getCurrentSegment
     * 对任意真实墙钟分钟都确定命中（测试跨天也不假绿）。
     */
    private fun fullDaySegment(listApps: List<String>?) = TimeSegment(
        startHour = 0, startMinute = 0,
        endHour = 24, endMinute = 0,
        limitMins = 60,
        name = "全天",
        listApps = listApps
    )

    /** 注入一条 AppWindow 时间窗规则：inside=true 全天窗口；false 则当前分钟必不在窗口内。 */
    private fun writeWindowRule(pkg: String, insideWindow: Boolean) {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start: Int
        val end: Int
        if (insideWindow) {
            // 全天窗口 [0, 1439]，覆盖每个分钟（start != end，避免命中「全天开放」特判分支）。
            start = 0
            end = 1439
        } else {
            // 取「当前分钟前 10~9 分钟」的 1 分钟窗口，保证 isMinuteInWindow(nowMin) == false。
            start = (nowMin - 10 + 1440) % 1440
            end = (nowMin - 9 + 1440) % 1440
        }
        val rule = AppWindowManager.AppWindowRule(
            id = "rule-timed",
            name = "timed-window",
            startTime = start,
            endTime = end,
            packageNames = mutableSetOf(pkg),
            isEnabled = true,
            daysOfWeek = (1..7).toSet()
        )
        val json = AppWindowManager.rulesToJsonForPublic(listOf(rule))
        appWindowPrefs.edit().putString("rules_json", json).commit()
        AppWindowManager.invalidateCache()
    }

    // ────────────────────────────────────────────────────────────
    //  S3 继承态：effectiveList == 全局黑名单 − 硬剔除
    // ────────────────────────────────────────────────────────────

    @Test
    fun inheritedEffectiveList_equalsGlobalBlacklist_minusHardExempt() {
        writeBlacklist(setOf(normalGame, launcher, settings, ime, phone, messaging))
        val seg = fullDaySegment(listApps = null)

        assertEquals(
            "继承态：有效名单 = 全局黑名单 − 硬剔除",
            setOf(normalGame),
            SegmentBlacklist.effectiveList(context, seg)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  S4 脱钩态：effectiveList == 名单 − 硬剔除；全局黑名单增删无影响
    // ────────────────────────────────────────────────────────────

    @Test
    fun decoupledEffectiveList_equalsList_minusHardExempt_globalChangesNoEffect() {
        writeBlacklist(setOf(wechat, normalGame))
        val seg = fullDaySegment(listApps = listOf(qq, launcher, settings, ime, phone, messaging))

        assertEquals(
            "脱钩态：有效名单 = 名单 − 硬剔除",
            setOf(qq),
            SegmentBlacklist.effectiveList(context, seg)
        )

        // 全局黑名单增删不影响脱钩态
        writeBlacklist(setOf(wechat, normalGame, "com.example.youtube"))
        assertEquals(
            "全局黑名单增加应用不影响脱钩态有效名单",
            setOf(qq),
            SegmentBlacklist.effectiveList(context, seg)
        )
        writeBlacklist(emptySet())
        assertEquals(
            "全局黑名单清空不影响脱钩态有效名单",
            setOf(qq),
            SegmentBlacklist.effectiveList(context, seg)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  S5 硬剔除优先：launcher/设置/输入法/电话/联系人/短信/IronWill 永不锁
    // ────────────────────────────────────────────────────────────

    @Test
    fun isHardExempt_coversSelfLauncherSettingsImePhoneContactsSms() {
        assertTrue("IronWill 自身必须硬剔除", SegmentBlacklist.isHardExempt(context, context.packageName))
        assertTrue("launcher 必须硬剔除", SegmentBlacklist.isHardExempt(context, launcher))
        assertTrue("设置必须硬剔除", SegmentBlacklist.isHardExempt(context, settings))
        assertTrue("输入法必须硬剔除", SegmentBlacklist.isHardExempt(context, ime))
        assertTrue("电话必须硬剔除", SegmentBlacklist.isHardExempt(context, phone))
        assertTrue("联系人必须硬剔除", SegmentBlacklist.isHardExempt(context, contacts))
        assertTrue("短信必须硬剔除", SegmentBlacklist.isHardExempt(context, messaging))
        assertTrue("系统导航 SystemUI 必须硬剔除", SegmentBlacklist.isHardExempt(context, systemui))
        assertTrue("vivo systemuiplugin 必须硬剔除", SegmentBlacklist.isHardExempt(context, vivoSystemUiPlugin))
        assertTrue("vivo upslide(系统导航) 必须硬剔除", SegmentBlacklist.isHardExempt(context, vivoUpslide))
        assertTrue("系统跟踪 traceur 必须硬剔除", SegmentBlacklist.isHardExempt(context, traceur))
        assertTrue("闹钟时钟 BBKClock 必须硬剔除", SegmentBlacklist.isHardExempt(context, clock))
        assertTrue("桌面时钟 deskclock 必须硬剔除", SegmentBlacklist.isHardExempt(context, deskclock))

        assertFalse("普通应用不得硬剔除", SegmentBlacklist.isHardExempt(context, normalGame))
        assertFalse("普通应用不得硬剔除", SegmentBlacklist.isHardExempt(context, qq))
    }

    @Test
    fun effectiveList_excludesHardExemptAppsEvenWhenInList() {
        val seg = fullDaySegment(
            listApps = listOf(normalGame, launcher, settings, ime, phone, contacts, messaging, systemui, vivoSystemUiPlugin, context.packageName)
        )

        assertEquals(
            "全选场景：硬剔除项即使在名单里也不进有效名单",
            setOf(normalGame),
            SegmentBlacklist.effectiveList(context, seg)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  核心「取代语义」：段内名单应用（不在全局黑名单）→ 命中
    // ────────────────────────────────────────────────────────────

    @Test
    fun segmentOnlyApp_notInGlobalBlacklist_isInEffectiveList_true() {
        writeBlacklist(emptySet())
        val seg = fullDaySegment(listApps = listOf(segOnly))

        assertTrue(
            "核心取代语义：仅在本时段名单、不在全局黑名单的应用必须命中有效名单",
            SegmentBlacklist.isInEffectiveList(context, seg, segOnly)
        )
        assertFalse(
            "不在名单的应用不得命中",
            SegmentBlacklist.isInEffectiveList(context, seg, normalGame)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  shouldLock：脱钩时段按名单、非脱钩按全局
    // ────────────────────────────────────────────────────────────

    @Test
    fun shouldLock_decoupledUsesSegmentList() {
        writeSegments(
            listOf(fullDaySegment(listApps = listOf(segOnly, launcher, settings, ime, phone, messaging)))
        )
        writeBlacklist(setOf(normalGame)) // 全局黑名单与名单不同

        assertTrue("脱钩时段：名单内应用应锁", SegmentBlacklist.shouldLock(context, segOnly))
        assertFalse(
            "脱钩时段：仅全局黑名单（不在名单）的应用不应锁",
            SegmentBlacklist.shouldLock(context, normalGame)
        )
        // 硬剔除在脱钩时段同样不锁
        assertFalse("launcher 不锁", SegmentBlacklist.shouldLock(context, launcher))
        assertFalse("设置不锁", SegmentBlacklist.shouldLock(context, settings))
        assertFalse("电话不锁", SegmentBlacklist.shouldLock(context, phone))
        assertFalse("IronWill 自身不锁", SegmentBlacklist.shouldLock(context, context.packageName))
    }

    @Test
    fun shouldLock_inheritedUsesGlobalBlacklist() {
        writeSegments(listOf(fullDaySegment(listApps = null)))
        writeBlacklist(setOf(normalGame))

        assertTrue("继承态：全局黑名单应用应锁", SegmentBlacklist.shouldLock(context, normalGame))
        assertFalse("继承态：非全局黑名单应用不应锁", SegmentBlacklist.shouldLock(context, segOnly))
    }

    // ────────────────────────────────────────────────────────────
    //  S6 时间窗仍拦：脱钩段 managed 应用不在名单 + 时间窗外 → 拦；窗口内 → 放行
    // ────────────────────────────────────────────────────────────

    @Test
    fun timeWindow_managedAppDecoupledNotInList_outsideWindow_blocked() {
        writeWindowRule(timed, insideWindow = false)
        writeSegments(listOf(fullDaySegment(listApps = listOf(segOnly)))) // timed 不在名单

        val isBlacklisted = SegmentBlacklist.isInEffectiveList(context, fullDaySegment(listOf(segOnly)), timed)
        val isManaged = AppWindowManager.isManaged(context, timed)
        val isAppWindowBlocked = !AppWindowManager.isAppAllowed(context, timed)

        assertFalse("脱钩段不在名单 → isBlacklisted=false", isBlacklisted)
        assertTrue("managed 应用", isManaged)
        assertTrue(
            "时间窗外 → isAppWindowBlocked=true（生产 shouldBlock 的 segmentDecoupled && isManaged 分支 → 拦）",
            isAppWindowBlocked
        )
    }

    @Test
    fun timeWindow_managedAppDecoupledNotInList_insideWindow_allowed() {
        writeWindowRule(timed, insideWindow = true)
        writeSegments(listOf(fullDaySegment(listApps = listOf(segOnly))))

        val isBlacklisted = SegmentBlacklist.isInEffectiveList(context, fullDaySegment(listOf(segOnly)), timed)
        val isManaged = AppWindowManager.isManaged(context, timed)
        val isAppWindowBlocked = !AppWindowManager.isAppAllowed(context, timed)

        assertFalse("脱钩段不在名单 → isBlacklisted=false", isBlacklisted)
        assertTrue("managed 应用", isManaged)
        assertFalse("时间窗内 → isAppWindowBlocked=false（放行）", isAppWindowBlocked)
    }
}
