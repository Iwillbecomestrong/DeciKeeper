/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacySpaceGuardEnforcementTest.kt
 * * @role: Test
 * * @description: TASK-01 隐私空间拦截门禁解耦的 TDD 单测。
 * 覆盖:
 * - PrivacyProtectionManager.isEnforcing() 三态映射(DISABLED/ACTIVE/PENDING 未到期/PENDING 到期)
 * - AntiUninstallProtection.check() 隐私 vs 防卸载 双开关四象限门禁
 * - 负样本: safe-package / self-package / 非 add-app 页 / 目标不含 IronWill
 * - 顺序: 第二套仍先于 App Info 分支
 * - 日志契约: 防卸载 DISABLED + 隐私执行时, :395-396 诊断事件不新增
 * * @context:
 * - 上游: Gradle Unit Test (Robolectric SDK 33)
 * - 下游: PrivacyProtectionManager, AntiUninstallProtection
 * * @constraints (必读):
 * 1. 不复制检测算法 - 所有断言通过调用生产判断与读取实例内部生产状态
 * 2. 不依赖真实设备, 不执行 adb install / uninstall
 * =================================================================================
 */
package com.zhizhi.app

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhizhi.app.manager.AntiUninstallManager
import com.zhizhi.app.manager.PrivacyProtectionManager
import com.zhizhi.app.protection.AntiUninstallProtection
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config




/**
 * TASK-01 端到端单元测试。
 *
 * 关键设计:
 * - Manager 三态使用 Robolectric 真实 SharedPreferences, 不绕开生产代码
 * - check() 四象限使用真实 AccessibilityNodeInfo 树, 通过反射读取生产实例内部状态
 *   (privateSpaceSessionUntil / lastPrivateSpaceBlockAt / lastPrivateSpaceMatchedSample)
 *   证明第二套路径实际被穿过, 而非复制检测算法
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacySpaceGuardEnforcementTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // 清空 Manager / Pending 两个 prefs, 保证每个测试独立
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("iron_will_rules_baseline", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        // 测试结束后回收所有创建的 AccessibilityNodeInfo, 防止 Robolectric 警告
        ShadowAccessibilityNodeInfoReset.resetObtainedInstances()
    }

    // =========================================================================
    // 第一组: PrivacyProtectionManager.isEnforcing() 三态映射
    // =========================================================================

    /** TC1: 无 key → DISABLED, isEnforcing=false */
    @Test
    fun isEnforcing_false_whenNoKey() {
        // 验证前置: prefs 是空的
        assertEquals(
            "无 key 时 getProtectionState 必须为 DISABLED",
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
        // 关键断言: isEnforcing == false
        assertFalse(
            "isEnforcing 在无 key 时必须返回 false",
            PrivacyProtectionManager.isEnforcing(context)
        )
    }

    /** TC2: setProtectionEnabled(true) → ACTIVE, isEnforcing=true */
    @Test
    fun isEnforcing_true_whenActive() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        assertEquals(
            "setProtectionEnabled(true) 必须直接落到 ACTIVE",
            PrivacyProtectionManager.State.ACTIVE,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertTrue(
            "isEnforcing 在 ACTIVE 时必须返回 true",
            PrivacyProtectionManager.isEnforcing(context)
        )
    }

    /** TC3: 非适应期 + baseline=true → 关闭进入 PENDING, isEnforcing=true */
    @Test
    fun isEnforcing_true_whenPendingNotYetDue() {
        // 先开, 写 baseline=true, 模拟"非适应期且 baseline 已 ACTIVE"
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        writeBaselinePrivacyProtection(true)

        // 通过 prefs 直接写入"远未来"的 pending, 强制进入 PENDING 路径
        // (绕过 setProtectionEnabled 内部 strictNextDay 的复杂计算)
        val pendingPrefs = context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
        pendingPrefs.edit()
            .putString(PrivacyProtectionManager.KEY_PENDING_DISABLE, "false")
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", Long.MAX_VALUE / 2)
            .commit()

        assertEquals(
            "远未来的 pending 必须落到 PENDING",
            PrivacyProtectionManager.State.PENDING,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertTrue(
            "isEnforcing 在未到期 PENDING 时必须返回 true",
            PrivacyProtectionManager.isEnforcing(context)
        )
    }

    /** TC4: PENDING 到期 → 自动落地 DISABLED, isEnforcing=false */
    @Test
    fun isEnforcing_false_whenPendingDue() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val pendingPrefs = context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
        // 写入"已过期"的 pending
        pendingPrefs.edit()
            .putString(PrivacyProtectionManager.KEY_PENDING_DISABLE, "false")
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", 1L) // 1970+1ms 必然到期
            .commit()

        // 第一次读取触发落地
        assertEquals(
            "到期 PENDING 必须落地为 DISABLED",
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertFalse(
            "isEnforcing 在到期后必须返回 false",
            PrivacyProtectionManager.isEnforcing(context)
        )

        // 落地的副作用: KEY_IS_ENABLED 应已被清为 false
        val mainPrefs = context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(
            "落地后 KEY_IS_ENABLED 必须为 false",
            mainPrefs.getBoolean(PrivacyProtectionManager.KEY_IS_ENABLED, true)
        )
    }

    // =========================================================================
    // 第二组: AntiUninstallProtection.check() 四象限门禁
    // =========================================================================

    /**
     * 隐私 ACTIVE + 防卸载 ACTIVE → 进入第二套, 应能捕获 add-app + IronWill
     */
    @Test
    fun check_blocksWhenBothPrivacyAndAntiUninstallActive() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        // 证明路径穿过 refreshPrivateSpaceSession (privacy session 已被激活)
        assertTrue(
            "第二套应被穿过: privateSpaceSessionUntil 必须 > 0",
            readPrivateLong(protection, "privateSpaceSessionUntil") > 0L
        )
        // 证明路径穿过 shouldBlockPrivateSpaceAddApp 并命中
        assertTrue(
            "第二套命中应记录 lastPrivateSpaceBlockAt",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
    }

    /**
     * 隐私 ACTIVE + 防卸载 DISABLED → 仍必须进入第二套 (门禁解耦核心)
     */
    @Test
    fun check_blocksWhenPrivacyActiveAndAntiUninstallDisabled() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, false)

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        assertTrue(
            "隐私 ACTIVE 时即使防卸载 DISABLED, 第二套仍必须被穿过",
            readPrivateLong(protection, "privateSpaceSessionUntil") > 0L
        )
        assertTrue(
            "隐私 ACTIVE 时即使防卸载 DISABLED, 第二套仍必须命中 add-app",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
    }

    /**
     * 隐私 PENDING 未到期 + 防卸载 DISABLED → 仍必须进入第二套
     */
    @Test
    fun check_blocksWhenPrivacyPendingAndAntiUninstallDisabled() {
        // 写入未到期 PENDING
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PrivacyProtectionManager.KEY_IS_ENABLED, true).commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PrivacyProtectionManager.KEY_PENDING_DISABLE, "false")
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", Long.MAX_VALUE / 2)
            .commit()

        // 确认 PENDING
        assertEquals(
            PrivacyProtectionManager.State.PENDING,
            PrivacyProtectionManager.getProtectionState(context)
        )

        AntiUninstallManager.setProtectionEnabled(context, false)

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        assertTrue(
            "PENDING 未到期时即使防卸载 DISABLED, 第二套仍必须被穿过",
            readPrivateLong(protection, "privateSpaceSessionUntil") > 0L
        )
        assertTrue(
            "PENDING 未到期时即使防卸载 DISABLED, 第二套仍必须命中 add-app",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
    }

    /**
     * 隐私 DISABLED + 防卸载 ACTIVE → 第二套不被穿过 (不授权)
     */
    @Test
    fun check_skipsSecondSuiteWhenPrivacyDisabledButAntiUninstallActive() {
        // 隐私默认 DISABLED
        assertFalse(PrivacyProtectionManager.isEnforcing(context))

        AntiUninstallManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        assertEquals(
            "隐私 DISABLED 时第二套必须不被激活",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
        assertEquals(
            "隐私 DISABLED 时第二套必须不命中",
            0L,
            readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        )
    }

    /**
     * 隐私 PENDING 到期 + 防卸载 ACTIVE → 第二套不被穿过 (端到端负样本)
     *
     * Luna R-? 要求: "PENDING 到期→不再拦" 必须在穿过 AntiUninstallProtection.check()
     * 的端到端测试中验证, 而不仅仅是 Manager 状态层。本测试:
     * 1. 写入已过期的 PENDING (effective_time = 1L) → 首次读取时落地为 DISABLED,
     *    isEnforcing() 返回 false
     * 2. 仍以命中 IronWill 的 add-app 事件调用 check()
     * 3. 断言第二套根本未被穿过 (privateSpaceSessionUntil == 0,
     *    lastPrivateSpaceBlockAt == 0), 不产生 PRIVATE_SPACE_ADD_APP 拦截
     */
    @Test
    fun check_skipsSecondSuiteWhenPrivacyPendingExpiredAndAntiUninstallActive() {
        // 1) 隐私: 先开 → 写已到期 PENDING, 触发 DISABLED 落地
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        val pendingPrefs = context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
        pendingPrefs.edit()
            .putString(PrivacyProtectionManager.KEY_PENDING_DISABLE, "false")
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", 1L)
            .commit()

        // 验证前置: PENDING 已到期, getProtectionState 自动落地为 DISABLED, isEnforcing == false
        assertEquals(
            "PENDING 到期前置条件: state 必须落地为 DISABLED",
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertFalse(
            "PENDING 到期前置条件: isEnforcing 必须为 false",
            PrivacyProtectionManager.isEnforcing(context)
        )

        // 2) 防卸载: 保持 ACTIVE, 隔离验证"隐私开关关闭"才是跳过第二套的唯一原因
        AntiUninstallManager.setProtectionEnabled(context, true)

        // 3) 端到端: 与正向用例完全相同的命中 IronWill 的 add-app 输入
        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        // 4) 断言: 第二套不被穿过 + 不命中
        assertEquals(
            "PENDING 到期端到端: 第二套必须不被激活 (privateSpaceSessionUntil == 0)",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
        assertEquals(
            "PENDING 到期端到端: 第二套必须不命中 (lastPrivateSpaceBlockAt == 0, 不产生 PRIVATE_SPACE_ADD_APP)",
            0L,
            readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        )
    }

    /**
     * 隐私 DISABLED + 防卸载 DISABLED → 整个受控区域提前退出
     */
    @Test
    fun check_earlyExitWhenBothDisabled() {
        // 默认两者都是 DISABLED
        assertFalse(PrivacyProtectionManager.isEnforcing(context))
        assertEquals(
            AntiUninstallManager.State.DISABLED,
            AntiUninstallManager.getProtectionState(context)
        )

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNode())

        // 两者都关 → 任何生产副作用都不应触发
        assertEquals(
            "双开关关闭时, 第二套不能激活",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
        assertEquals(
            "双开关关闭时, 防卸载 lastBlockTime 不能推进",
            0L,
            readPrivateLong(protection, "mLastBlockTime")
        )
    }

    // =========================================================================
    // 第三组: 负样本 (safe / self / 非 add-app / 目标不含 IronWill)
    // =========================================================================

    @Test
    fun check_skipsWhenSafePackage() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        // 当前包名匹配 SAFE_PACKAGES_KEYWORDS ("intentresolver")
        val event = buildEvent(currentPackage = "com.android.intentresolver")
        val root = buildAddAppRootNode() // 即使节点含 add-app 信号, safe package 也应早退

        protection.check(buildService(), event, root)

        assertEquals(
            "safe package 早退: 不应激活第二套",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
    }

    @Test
    fun check_skipsWhenSelfPackage() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        val event = buildEvent(currentPackage = context.packageName)
        val root = buildAddAppRootNode()

        protection.check(buildService(), event, root)

        assertEquals(
            "self package 早退: 不应激活第二套",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
    }

    @Test
    fun check_skipsWhenSelfPackageRoot() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        // currentPackage 是 privacy, 但 root.packageName 是 self
        val event = buildEvent(currentPackage = "com.vivo.abe")
        val root = buildAddAppRootNode(rootPackageName = context.packageName)

        protection.check(buildService(), event, root)

        assertEquals(
            "root self package 早退: 不应激活第二套",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
    }

    @Test
    fun check_skipsWhenNotAddAppPage() {
        // 2026-07-22 three-bugs round 行为变更: 闸 1 命中即返回 true (去除第二闸"列表含 IronWill"判定)
        // 因此 AddApp 页 (即使列表里没有 IronWill) 也会被立即拦截
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        // 清零上次测试的 lastPrivateSpaceBlockAt (test 顺序敏感, 上一个测试可能已写入)
        writePrivateLong(protection, "lastPrivateSpaceBlockAt", 0L)
        // 包名是 privacy, 但 className 不是 AppMoveInActivity (是首页/设置等) → 不是 AddApp 页
        val event = buildEventWithClass(currentPackage = "com.vivo.abe", className = "com.vivo.privacy.MainActivity")
        val root = buildPlainNode("隐私系统首页")

        protection.check(buildService(), event, root)

        // privacy session 仍被激活
        assertTrue(
            "privacy session 应被激活",
            readPrivateLong(protection, "privateSpaceSessionUntil") > 0L
        )
        // 非 AddApp 页: 不应 block (闸 1 不命中)
        assertEquals(
            "非 AddApp 页: 不应命中 block",
            0L,
            readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        )
    }

    @Test
    fun check_blocksAddAppPageEvenWithoutIronWillInTarget() {
        // 2026-07-22 three-bugs round 行为变更: 闸 1 (AddApp 页) 命中即返回 true
        // 不再要求列表里含 IronWill 文本, AddApp 页面打开瞬间即拦
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        // AddApp 页但目标列表里没有 IronWill (只含 "微信")
        val event = buildEvent(currentPackage = "com.vivo.abe")
        val root = buildAddAppRootNode(targetAppName = "微信")

        protection.check(buildService(), event, root)

        // 闸 1 命中: 立即记录 block
        assertTrue(
            "AddApp 页即使无 IronWill 也应立即命中 block",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
    }

    // =========================================================================
    // 第四组: 顺序 / 日志契约
    // =========================================================================

    @Test
    fun check_privacyGateDoesNotAuthorizeDiagnosticEventLog() {
        // 防卸载 DISABLED + 隐私 ACTIVE
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        AntiUninstallManager.setProtectionEnabled(context, false)

        val protection = newProtection()
        // STATE_CHANGED 事件会触发 :395-396 诊断事件日志
        val event = buildAddAppEvent()

        protection.check(buildService(), event, buildAddAppRootNode())

        // 读取 LogExporter 内部日志文件, 确认 TYPE_WINDOW_STATE_CHANGED 的 [Event] 行不存在
        val logFile = java.io.File(context.filesDir, "iron_will_normal_event.log")
        // 这个测试只断言"诊断事件日志契约", 与实现细节解耦:
        // 若防卸载 DISABLED, [Event] 行不应被写入
        val hasEventLog = if (logFile.exists()) {
            logFile.readText().contains("[Event] Pkg=")
        } else false

        // 由于 :395-396 只在防卸载执行时才采集, 防卸载 DISABLED 时不应新增
        // 注意: 这里只关心"本次 check 是否新增", 此前没有写入 → 文件存在与否都不应有 [Event] 内容
        assertFalse(
            "防卸载 DISABLED 时, :395-396 诊断事件日志不应被隐私开关授权",
            hasEventLog
        )
    }

    // =========================================================================
    // 测试工具
    // =========================================================================

    private fun newProtection(): AntiUninstallProtection =
        AntiUninstallProtection(context)

    private fun writeBaselinePrivacyProtection(enabled: Boolean) {
        context.getSharedPreferences("iron_will_rules_baseline", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PrivacyProtectionManager.KEY_IS_ENABLED, enabled)
            .commit()
    }

    /** 反射读取 AntiUninstallProtection 私有字段 (Long 类型) */
    private fun readPrivateLong(target: Any, name: String): Long {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.getLong(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("No field $name on ${target.javaClass.name}")
    }

    /** 反射写入 AntiUninstallProtection 私有字段 (Long 类型), 用于重置 test 顺序敏感性 */
    private fun writePrivateLong(target: Any, name: String, value: Long) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                field.setLong(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("No field $name on ${target.javaClass.name}")
    }

    /**
     * 构造 vivo 隐私空间的 "添加应用" 页面 root 节点。
     *
     * 必须满足:
     * - root.packageName = "com.vivo.abe" (命中 PRIVATE_SPACE_PACKAGES)
     * - text 含 "添加应用" (命中 PRIVATE_SPACE_ADD_APP_TEXT_KEYWORDS)
     * - className 含 "list" (命中 PRIVATE_SPACE_LIST_CLASS_HINTS)
     * - 子节点 text 含 "IronWill" (命中 PROTECTED_APP_NAMES, 通过 shouldBlockPrivateSpaceVisibleTarget)
     */
    private fun buildAddAppRootNode(
        rootPackageName: String = "com.vivo.abe",
        targetAppName: String = "IronWill"
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = rootPackageName
        root.className = "android.widget.ListView"
        root.text = "添加应用"

        // 列表中 IronWill 条目 (目标)
        val targetNode = AccessibilityNodeInfo.obtain()
        targetNode.packageName = rootPackageName
        targetNode.className = "android.widget.TextView"
        targetNode.text = targetAppName

        // 添加子节点 (ShadowAccessibilityNodeInfo.addChild)
        ShadowAddChild.addChild(root, targetNode)
        targetNode.recycle()

        return root
    }

    /** 构造一个普通 privacy 页面 (含包名匹配但不含 add-app 信号) */
    private fun buildPlainNode(text: String): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.vivo.abe"
        root.className = "android.widget.TextView"
        root.text = text
        return root
    }

    /** 构造 AccessibilityEvent, 模拟隐私空间 add-app 页面切换 */
    private fun buildAddAppEvent(): AccessibilityEvent =
        buildEvent(currentPackage = "com.vivo.abe", text = "添加应用")

    private fun buildEvent(
        currentPackage: String,
        text: String = ""
    ): AccessibilityEvent {
        return buildEventWithClass(currentPackage, "com.vivo.privacy.AppMoveInActivity", text)
    }

    private fun buildEventWithClass(
        currentPackage: String,
        className: String,
        text: String = ""
    ): AccessibilityEvent {
        val event = AccessibilityEvent.obtain()
        event.eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        event.packageName = currentPackage
        event.className = className
        // AccessibilityEvent.text 不可赋值, 通过构造路径注入;
        // 实际生产判断以 root.collectAllText 为准, event.text 仅影响 [:395-396] 诊断行
        return event
    }

    /** 构造一个不会做事的 stub AccessibilityService */
    private fun buildService(): android.accessibilityservice.AccessibilityService {
        // AccessibilityService 是 final concrete class (不能子类化 performGlobalAction),
        // 但 onAccessibilityEvent / onInterrupt 是 abstract. 在 Robolectric 环境下
        // 直接 new 一个匿名子类实现这两个 abstract 方法的空操作, performGlobalAction
        // 走 Robolectric shadow, 默认返回 false (不真正执行全局动作). 这满足本测试
        // 仅观察"路径穿过"的需要.
        return object : android.accessibilityservice.AccessibilityService() {
            override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
                // no-op for test
            }
            override fun onInterrupt() {
                // no-op for test
            }
        }
    }
}

/**
 * 简化的 ShadowAccessibilityNodeInfo 反射访问封装,
 * 避免直接 import Robolectric 内部类污染测试代码。
 */
private object ShadowAccessibilityNodeInfoReset {
    fun resetObtainedInstances() {
        try {
            val cls = Class.forName("org.robolectric.shadows.ShadowAccessibilityNodeInfo")
            val method = cls.getMethod("resetObtainedInstances")
            method.invoke(null)
        } catch (_: Exception) {
            // Robolectric 不可用时静默
        }
    }
}

private object ShadowAddChild {
    fun addChild(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        try {
            val cls = Class.forName("org.robolectric.shadows.ShadowAccessibilityNodeInfo")
            val method = cls.getMethod("addChild", AccessibilityNodeInfo::class.java)
            // Shadow 实例方法 → 从 parent 的 shadow 调用
            val shadowOfParent = org.robolectric.Shadows.shadowOf(parent)
            method.invoke(shadowOfParent, child)
        } catch (e: Exception) {
            throw AssertionError("Failed to add child via Robolectric shadow: ${e.message}", e)
        }
    }
}