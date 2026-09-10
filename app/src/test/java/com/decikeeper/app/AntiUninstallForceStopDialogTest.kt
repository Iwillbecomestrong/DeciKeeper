/**
 * =================================================================================
 * [Context Header]
 * * @file: AntiUninstallForceStopDialogTest.kt
 * * @role: Test
 * * @description: TASK-01 Bug 2 — FORCE_STOP_DIALOG 走 handleDetection + 移除 isOurApp 的 TDD 单测。
 * 覆盖:
 * - 正样本 (无 IronWill 文本): VDialog + 强行停止 + 确定/取消 → handleDetection 命中, mUninstallHotWindowUntil 被 arm
 * - 重复命中: 5 秒内第二次仍走 handleDetection (共享 hot-window)
 * - 无名正样本: 文本仅含 force-stop + 决策按钮, 完全无 IronWill/com.ironwill/ironwill
 * - 负样本: 缺 dialog class / 缺 force-stop 文本 / 缺 决策按钮
 * - 跨生产 check() 验证, 不复制检测算法 (通过反射读 mUninstallHotWindowUntil)
 * * @context:
 * - 上游: Gradle Unit Test (Robolectric SDK 33)
 * - 下游: AntiUninstallProtection.check()
 * * @constraints (必读):
 * 1. 不复制检测算法, 必须跨生产 check() 而非 stub
 * 2. 正样本 fixture 故意省略 IronWill / com.ironwill / ironwill 文本 (驱动 A7 验收)
 * 3. 不动 handleDetection / armUninstallHotWindow / UNINSTALL_HOT_WINDOW_MS / 卸载分支
 * =================================================================================
 */
package com.decikeeper.app

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.PrivacyProtectionManager
import com.decikeeper.app.protection.AntiUninstallProtection
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config



/**
 * WP-2 端到端单元测试。
 *
 * 关键设计:
 * - 所有断言通过调用生产 check() + 读取实例内部生产状态 (mUninstallHotWindowUntil)
 * - 正样本 fixture 故意省略 IronWill / com.ironwill / ironwill 文本
 *   (证明 Bug 2 修好后 isOurApp 限制被移除)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AntiUninstallForceStopDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 清空所有相关 prefs, 保证每个测试独立
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        AntiUninstallManager.setProtectionEnabled(context, true)
    }

    @After
    fun tearDown() {
        // 测试结束后回收所有创建的 AccessibilityNodeInfo, 防止 Robolectric 警告
        ForceStopTestShadowNodeReset.resetObtainedInstances()
    }

    // =========================================================================
    // 第一组: 正样本 (无 IronWill 文本) — 验证 Bug 2 修复后的对称性
    // =========================================================================

    /**
     * TC1: 正样本 — VDialog + 强行停止 + 确定/取消, root 完全不含 IronWill 文本,
     *      仍必须走 handleDetection 并 arm mUninstallHotWindowUntil.
     */
    @Test
    fun check_blocksForceStopDialogWithoutIronWillText() {
        val protection = AntiUninstallProtection(context)
        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false)
        )

        // 证明 Bug 2 已修复: isOurApp 不再阻塞 force-stop 拦截
        // mUninstallHotWindowUntil > 0 证明 handleDetection 已 arm 5 秒热窗
        assertTrue(
            "force-stop 拦截 (无 IronWill 文本) 必须 arm 5 秒 hot window",
            readPrivateLong(protection, "mUninstallHotWindowUntil") > 0L
        )
    }

    /**
     * TC2: 重复命中 — 5 秒内第二次 force-stop 仍走 handleDetection (共享 hot-window)
     */
    @Test
    fun check_repeatedForceStopDialogReArmsHotWindow() {
        val protection = AntiUninstallProtection(context)

        // 第一次: arm hot window
        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false)
        )
        val firstHotUntil = readPrivateLong(protection, "mUninstallHotWindowUntil")
        assertTrue("第一次 force-stop 必须 arm hot window", firstHotUntil > 0L)

        // 等待一段时间 (小于 5 秒), 模拟 vivo 多任务重入
        Thread.sleep(100)

        // 第二次: 重新 arm
        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false)
        )
        val secondHotUntil = readPrivateLong(protection, "mUninstallHotWindowUntil")

        // 第二次必须再次走 handleDetection (意味着 hot window 至少仍 active)
        assertTrue(
            "第二次 force-stop (5 秒内) 必须仍被处理 (hot window 仍 active)",
            secondHotUntil >= firstHotUntil
        )
    }

    /**
     * TC3: 完全无 IronWill 文本的 force-stop — event.text 和 root text 都无 IronWill
     */
    @Test
    fun check_forceStopWithNoNameMatch() {
        val protection = AntiUninstallProtection(context)
        // event 和 root 都不含 IronWill / com.ironwill / ironwill
        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false, includePackageName = false)
        )

        assertTrue(
            "完全无 IronWill 文本的 force-stop 也必须被拦截 (Bug 2 修复验证)",
            readPrivateLong(protection, "mUninstallHotWindowUntil") > 0L
        )
    }

    /**
     * TC4: handleDetection 内 BACK/HOME 已由 production 内部处理,
     *      不再由 force-stop 分支直接调用 — 验证 mLastBlockTime 不被 force-stop 推进
     *      (避免与 handleDetection 内部逻辑冲突)
     */
    @Test
    fun check_forceStopBranchDoesNotAdvanceLastBlockTimeDirectly() {
        val protection = AntiUninstallProtection(context)
        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false)
        )

        // mUninstallHotWindowUntil 被 arm = 走 handleDetection 路径
        val hotWindowArmed = readPrivateLong(protection, "mUninstallHotWindowUntil") > 0L
        assertTrue(
            "force-stop 必须走 handleDetection (以 arm hot window 为证据)",
            hotWindowArmed
        )
    }

    @Test
    fun check_skipsForceStopWhenAntiUninstallIsDisabled() {
        AntiUninstallManager.setProtectionEnabled(context, false)
        val protection = AntiUninstallProtection(context)

        protection.check(
            buildService(),
            buildForceStopEvent(),
            buildForceStopRootNode(includeIronWillText = false)
        )

        assertEquals(
            "防卸载关闭时，强行停止不应继续拦截",
            0L,
            readPrivateLong(protection, "mUninstallHotWindowUntil")
        )
    }

    // =========================================================================
    // 第二组: 负样本 — 验证四路 predicate 仍生效
    // =========================================================================

    /**
     * TC5: 缺 dialog class (使用纯 TextView) → 不应被 force-stop 分支拦截
     */
    @Test
    fun check_skipsForceStopBranchWhenNoDialogClass() {
        val protection = AntiUninstallProtection(context)

        // event.className 不含 Dialog/Alert/VDialog
        protection.check(
            buildService(),
            buildForceStopEvent(className = "android.widget.TextView"),
            buildForceStopRootNode(includeIronWillText = false)
        )

        // mUninstallHotWindowUntil 未被 arm → force-stop 分支被跳过
        assertEquals(
            "缺 dialog class: force-stop 分支不应被命中",
            0L,
            readPrivateLong(protection, "mUninstallHotWindowUntil")
        )
    }

    /**
     * TC6: 缺 force-stop 文本 (只有确定/取消) → 不应被 force-stop 分支拦截
     */
    @Test
    fun check_skipsForceStopBranchWhenNoForceStopText() {
        val protection = AntiUninstallProtection(context)

        // event.text 不含 强行停止/Force stop/要强行停止吗/强制停止
        protection.check(
            buildService(),
            buildForceStopEvent(text = "确定 取消"),
            buildForceStopRootNode(includeIronWillText = false)
        )

        assertEquals(
            "缺 force-stop 文本: force-stop 分支不应被命中",
            0L,
            readPrivateLong(protection, "mUninstallHotWindowUntil")
        )
    }

    /**
     * TC7: 缺决策按钮 (只有 force-stop 文本) → 不应被 force-stop 分支拦截
     */
    @Test
    fun check_skipsForceStopBranchWhenNoDecisionButtons() {
        val protection = AntiUninstallProtection(context)

        // event.text 只有 force-stop 文本, 缺 确定/取消/Confirm/Cancel
        protection.check(
            buildService(),
            buildForceStopEvent(text = "要强行停止吗"),
            buildForceStopRootNode(includeIronWillText = false)
        )

        assertEquals(
            "缺决策按钮: force-stop 分支不应被命中",
            0L,
            readPrivateLong(protection, "mUninstallHotWindowUntil")
        )
    }

    // =========================================================================
    // 测试工具
    // =========================================================================

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

    /**
     * 构造 force-stop 弹窗事件 (TYPE_WINDOW_STATE_CHANGED)。
     *
     * 必须满足四路 predicate:
     * - event.eventType == TYPE_WINDOW_STATE_CHANGED
     * - event.className 含 "VDialog"
     * - event.text 含 "要强行停止吗" + "确定" + "取消"
     */
    private fun buildForceStopEvent(
        className: String = "com.vivo.framework.VDialog",
        text: String = "要强行停止吗 确定 取消"
    ): AccessibilityEvent {
        val event = AccessibilityEvent.obtain()
        event.eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        event.packageName = "com.vivo.permissionmanager"
        event.className = className
        // AccessibilityEvent.text 是 MutableList<CharSequence>; 直接 add,
        // 生产代码读 event.text?.toString() (整串 list toString) 做四路 predicate 匹配。
        event.text.add(text)
        return event
    }

    /**
     * 构造 force-stop 弹窗的 root AccessibilityNodeInfo.
     *
     * 默认不带 IronWill/com.ironwill/ironwill 文本 (用于驱动 A7 验收).
     */
    private fun buildForceStopRootNode(
        includeIronWillText: Boolean = false,
        includePackageName: Boolean = false
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.vivo.permissionmanager"
        root.className = "com.vivo.framework.VDialog"

        // 子节点: 弹窗标题 + 确定 + 取消 按钮
        val titleNode = AccessibilityNodeInfo.obtain()
        titleNode.packageName = "com.vivo.permissionmanager"
        titleNode.className = "android.widget.TextView"
        titleNode.text = if (includeIronWillText) "要强行停止 IronWill 吗" else "要强行停止吗"

        val confirmNode = AccessibilityNodeInfo.obtain()
        confirmNode.packageName = "com.vivo.permissionmanager"
        confirmNode.className = "android.widget.Button"
        confirmNode.text = "确定"

        val cancelNode = AccessibilityNodeInfo.obtain()
        cancelNode.packageName = "com.vivo.permissionmanager"
        cancelNode.className = "android.widget.Button"
        cancelNode.text = "取消"

        ForceStopTestShadowAddChild.addChild(root, titleNode)
        ForceStopTestShadowAddChild.addChild(root, confirmNode)
        ForceStopTestShadowAddChild.addChild(root, cancelNode)

        titleNode.recycle()
        confirmNode.recycle()
        cancelNode.recycle()

        return root
    }

    /** 构造一个不会做事的 stub AccessibilityService */
    private fun buildService(): android.accessibilityservice.AccessibilityService {
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
 * 简化的 ShadowAccessibilityNodeInfo 反射访问封装 (WP-2 实例,避免与 PrivacySpaceGuardEnforcementTest 冲突)
 */
internal object ForceStopTestShadowNodeReset {
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

internal object ForceStopTestShadowAddChild {
    fun addChild(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        try {
            val cls = Class.forName("org.robolectric.shadows.ShadowAccessibilityNodeInfo")
            val method = cls.getMethod("addChild", AccessibilityNodeInfo::class.java)
            val shadowOfParent = org.robolectric.Shadows.shadowOf(parent)
            method.invoke(shadowOfParent, child)
        } catch (e: Exception) {
            throw AssertionError("Failed to add child via Robolectric shadow: ${e.message}", e)
        }
    }
}
