/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivateSpaceImmediateBlockTest.kt
 * * @role: Test
 * * @description: WP-3 Bug 3 隐私空间 AddApp 立即拦截 TDD 单测。
 * 覆盖:
 * - A10: AddApp 页闸 1 命中即返回 true (无需被保护应用文本/第二闸抓取)
 * - A11: 同一签名 1200ms 内连续触发: true / false / false (production timestamp 状态)
 * - A12: 反向契约 - shouldBlockPrivateSpaceAddApp / PRIVATE_SPACE_ADD_APP / refreshPrivateSpaceSession / helper 定义仍存活
 * - A12: 正向契约 - 活跃 AddApp 代码不再调用 shouldBlockPrivateSpaceVisibleTarget / findPrivateSpaceProtectedEvidence / selectPrivateSpaceMatchedEvidence / maybeLogPrivateSpaceMiss / collectPrivateSpaceEventSourceText
 * - 负样本: enforcement OFF / 非 AddApp 页 → 不拦截
 * * @context:
 * - 上游: Gradle Unit Test (Robolectric SDK 33)
 * - 下游: AntiUninstallProtection
 * * @constraints (必读):
 * 1. 不复制检测算法 - 通过生产路径 + 反射读取私有状态 + 源码字符串定位断言
 * 2. 不依赖真实设备, 不执行 adb install / uninstall
 * =================================================================================
 */
package com.zhizhi.app

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhizhi.app.manager.PrivacyProtectionManager
import com.zhizhi.app.protection.AntiUninstallProtection
import java.io.File
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config



/**
 * WP-3 Bug 3 立即拦截的端到端单元测试。
 *
 * 关键设计:
 * - 不复制检测算法 - 通过生产路径调用 + 反射读取 lastPrivateSpaceBlockAt/lastPrivateSpaceBlockSignature
 * - A10: 构造一个不包含 IronWill 文本的 add-app 页 → 闸 1 命中即返回 true (旧逻辑会 false)
 * - A11: 同一签名 1200ms 内三次调用: true / false / false (production state 验证)
 * - A12: 源码字符串扫描, 确认 helper 定义仍在但 active AddApp 路径不再调用它们
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivateSpaceImmediateBlockTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // 清空相关 prefs, 保证每个测试独立
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("iron_will_rules_baseline", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // 立即拦截 shadow 状态在每个测试前清零, 防止前一个测试残留 armed page context.
        ImmediateBlockShadowState.resetTrackedSignatures()
    }

    @After
    fun tearDown() {
        ImmediateBlockShadowAccessibilityNodeInfoReset.resetObtainedInstances()
    }

    // =========================================================================
    // 第一组: A10 - AddApp 闸 1 命中即返回 true (不需要被保护应用文本)
    // =========================================================================

    /**
     * TC1: AddApp 页面识别(没有 IronWill 文本) → 闸 1 命中应立即 block.
     * 旧逻辑: shouldBlockPrivateSpaceVisibleTarget 返回 false → 不 block.
     * 新逻辑: shouldBlockPrivateSpaceVisibleTarget 闸 2 被移除 → 应立即 block.
     */
    @Test
    fun addAppPage_firstGate_returnsTrueWithoutProtectedText() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        // shouldBlockPrivateSpaceAddApp 在 session 不活跃时直接 return false.
        // 直接调用前需先激活 session, 模拟 refreshPrivateSpaceSession 已跑过的状态.
        activatePrivateSpaceSession(protection)
        // 通过反射拿到 production 行为: 必须在没有 IronWill 文本时仍返回 true
        val result = invokeShouldBlockPrivateSpaceAddApp(
            protection,
            currentPackage = "com.vivo.abe",
            event = buildAddAppEvent(),
            root = buildAddAppRootNodeWithoutIronWill()
        )

        assertTrue(
            "AddApp 闸 1 命中应立即返回 true, 不要求 protected text",
            result
        )
        // 证明 production timestamp 已记录
        assertTrue(
            "lastPrivateSpaceBlockAt 应被推进",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
        // 证明 production signature 已记录
        assertNotNull(
            "lastPrivateSpaceBlockSignature 应被设置",
            readPrivateObject(protection, "lastPrivateSpaceBlockSignature")
        )
    }

    /**
     * TC2: 端到端 check() 路径 - 没有 IronWill 的 add-app 页仍必须触发 PRIVATE_SPACE_ADD_APP 拦截.
     */
    @Test
    fun check_blocksAddAppPageEvenWithoutIronWillInTarget() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        protection.check(
            buildService(),
            buildAddAppEvent(),
            buildAddAppRootNodeWithoutIronWill()
        )

        assertTrue(
            "check() 应穿过 shouldBlockPrivateSpaceAddApp 并命中 (lastPrivateSpaceBlockAt > 0)",
            readPrivateLong(protection, "lastPrivateSpaceBlockAt") > 0L
        )
    }

    // =========================================================================
    // 第二组: A11 - 同一签名 1200ms 内连续触发 (true / false / false)
    // =========================================================================

    /**
     * TC3: 同一 currentPackage/rootPackage/eventClass 三次调用, 期望:
     *   - 第 1 次: true (新签名, 写入 timestamp)
     *   - 第 2 次: false (同签名, 在 1200ms 内去抖)
     *   - 第 3 次: false (同上)
     * 验证 production timestamp 状态: 第一次写入后保持不变 (后续两次返回 false 时不更新 timestamp).
     */
    @Test
    fun debounce_sameSignatureSuppressedWithin1200ms() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        activatePrivateSpaceSession(protection)
        val event = buildAddAppEvent()
        val root = buildAddAppRootNodeWithoutIronWill()

        val first = invokeShouldBlockPrivateSpaceAddApp(protection, "com.vivo.abe", event, root)
        val timestampAfterFirst = readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        val signatureAfterFirst = readPrivateObject(protection, "lastPrivateSpaceBlockSignature") as? String
        assertNotNull("第一次返回后 signature 必须已写入", signatureAfterFirst)
        assertTrue("第一次返回后 timestamp 必须 > 0", timestampAfterFirst > 0L)

        val second = invokeShouldBlockPrivateSpaceAddApp(protection, "com.vivo.abe", event, root)
        val timestampAfterSecond = readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        assertEquals("第二次 timestamp 必须保持不变 (返回 false 不重置)", timestampAfterFirst, timestampAfterSecond)
        assertFalse("第二次返回应为 false (去抖抑制)", second)

        val third = invokeShouldBlockPrivateSpaceAddApp(protection, "com.vivo.abe", event, root)
        val timestampAfterThird = readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        assertEquals("第三次 timestamp 必须保持不变", timestampAfterFirst, timestampAfterThird)
        assertFalse("第三次返回应为 false (去抖抑制)", third)

        assertTrue("第一次必须返回 true", first)
    }

    // =========================================================================
    // 第三组: 负样本 (enforcement OFF / 非 AddApp 页)
    // =========================================================================

    /**
     * TC4: enforcement OFF → isEnforcing=false → check() 端到端第二套不被穿过.
     */
    @Test
    fun check_skipsAddAppWhenEnforcementOff() {
        // 默认 enforcement OFF
        assertFalse(PrivacyProtectionManager.isEnforcing(context))

        val protection = newProtection()
        protection.check(buildService(), buildAddAppEvent(), buildAddAppRootNodeWithoutIronWill())

        assertEquals(
            "enforcement OFF 时第二套不能激活",
            0L,
            readPrivateLong(protection, "privateSpaceSessionUntil")
        )
        assertEquals(
            "enforcement OFF 时 lastPrivateSpaceBlockAt 不能推进",
            0L,
            readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        )
    }

    /**
     * TC5: enforcement ON, 但不是 AddApp 页 (没有"添加应用"text, event class 不命中) → 不应 block.
     */
    @Test
    fun check_skipsWhenNotAddAppPage() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)

        val protection = newProtection()
        protection.check(
            buildService(),
            buildEvent(
                currentPackage = "com.vivo.abe",
                text = "隐私系统首页",
                className = "com.vivo.privacy.PrivacyHomeActivity"
            ),
            buildPlainNode(text = "隐私系统首页")
        )

        // 第二套被穿过 (因为是 privacy 包), 但 shouldBlockPrivateSpaceAddApp 应返回 false
        assertTrue(
            "privacy session 仍应被激活",
            readPrivateLong(protection, "privateSpaceSessionUntil") > 0L
        )
        assertEquals(
            "非 add-app 页: 不应命中 block",
            0L,
            readPrivateLong(protection, "lastPrivateSpaceBlockAt")
        )
    }

    // =========================================================================
    // 第四组: A12 - 反向契约 (helper 定义仍存活) + 正向契约 (active AddApp 路径不再调用 helper)
    // =========================================================================

    /**
     * TC6: A12 反向契约 - 生产标识与 dormant helper 定义仍存在.
     */
    @Test
    fun productionIdentifiers_andDormantHelpers_alive() {
        val src = readAntiUninstallSource()

        // production 标识必须仍存活
        assertTrue(
            "shouldBlockPrivateSpaceAddApp 必须仍存在",
            src.contains("fun shouldBlockPrivateSpaceAddApp(")
        )
        assertTrue(
            "PRIVATE_SPACE_ADD_APP log reason 必须仍存在",
            src.contains("PRIVATE_SPACE_ADD_APP")
        )
        assertTrue(
            "refreshPrivateSpaceSession 必须仍存在",
            src.contains("fun refreshPrivateSpaceSession(")
        )

        // dormant helper 定义必须仍存活 (作为 dead code 备用)
        assertTrue(
            "shouldBlockPrivateSpaceVisibleTarget (dormant helper) 必须仍存在",
            src.contains("fun shouldBlockPrivateSpaceVisibleTarget(")
        )
        assertTrue(
            "findPrivateSpaceProtectedEvidence (dormant helper) 必须仍存在",
            src.contains("fun findPrivateSpaceProtectedEvidence(")
        )
        assertTrue(
            "findProtectedAppEvidenceByText (dormant helper) 必须仍存在",
            src.contains("fun findProtectedAppEvidenceByText(")
        )
        assertTrue(
            "selectPrivateSpaceMatchedEvidence (dormant helper) 必须仍存在",
            src.contains("fun selectPrivateSpaceMatchedEvidence(")
        )
        assertTrue(
            "maybeLogPrivateSpaceMiss (dormant helper) 必须仍存在",
            src.contains("fun maybeLogPrivateSpaceMiss(")
        )
        assertTrue(
            "collectPrivateSpaceEventSourceText (dormant helper) 必须仍存在",
            src.contains("fun collectPrivateSpaceEventSourceText(")
        )
    }

    /**
     * TC7: A12 正向契约 - 活跃 AddApp 路径 (shouldBlockPrivateSpaceAddApp 函数体) 不再调用闸 2 helper.
     *
     * 策略: 定位 shouldBlockPrivateSpaceAddApp 函数体的右大括号行号, 然后扫描该范围,
     * 不应再包含任何"闸 2"调用 (visible target / protected evidence / matched evidence / miss log / event source text).
     */
    @Test
    fun activeAddAppBody_doesNotInvokeSecondGate() {
        val src = readAntiUninstallSource()

        val bodyStart = src.indexOf("fun shouldBlockPrivateSpaceAddApp(")
        assertTrue("必须找到 shouldBlockPrivateSpaceAddApp 函数", bodyStart >= 0)

        // 找到配对的右大括号 - 用简单花括号计数
        var braceCount = 0
        var bodyEnd = -1
        var foundOpen = false
        var i = bodyStart
        while (i < src.length) {
            val c = src[i]
            if (c == '{') {
                braceCount++
                foundOpen = true
            } else if (c == '}') {
                braceCount--
                if (foundOpen && braceCount == 0) {
                    bodyEnd = i
                    break
                }
            }
            i++
        }
        assertTrue("必须找到 shouldBlockPrivateSpaceAddApp 函数体结束", bodyEnd > bodyStart)

        val body = src.substring(bodyStart, bodyEnd)

        // 闸 2 active 调用必须不再出现
        assertFalse(
            "活跃 AddApp 路径不应再调用 shouldBlockPrivateSpaceVisibleTarget",
            body.contains("shouldBlockPrivateSpaceVisibleTarget(")
        )
        assertFalse(
            "活跃 AddApp 路径不应再调用 findPrivateSpaceProtectedEvidence",
            body.contains("findPrivateSpaceProtectedEvidence(")
        )
        assertFalse(
            "活跃 AddApp 路径不应再调用 selectPrivateSpaceMatchedEvidence",
            body.contains("selectPrivateSpaceMatchedEvidence(")
        )
        assertFalse(
            "活跃 AddApp 路径不应再调用 maybeLogPrivateSpaceMiss",
            body.contains("maybeLogPrivateSpaceMiss(")
        )
        assertFalse(
            "活跃 AddApp 路径不应再调用 collectPrivateSpaceEventSourceText",
            body.contains("collectPrivateSpaceEventSourceText(")
        )

        // 必须仍保留闸 1 调用 (isPrivateSpaceAddAppPageCandidate / armPrivateSpaceAddAppPageContext /
        //   isPrivateSpaceAddAppPageContextActive / maybeLogPrivateSpaceSkipScrape)
        assertTrue(
            "活跃 AddApp 路径必须仍调用 isPrivateSpaceAddAppPageCandidate (闸 1)",
            body.contains("isPrivateSpaceAddAppPageCandidate(")
        )
        assertTrue(
            "活跃 AddApp 路径必须仍调用 maybeLogPrivateSpaceSkipScrape (闸 1 miss log)",
            body.contains("maybeLogPrivateSpaceSkipScrape(")
        )
        // 必须保留 1200L debounce
        assertTrue(
            "活跃 AddApp 路径必须保留 1200L debounce",
            body.contains("1200L")
        )
        // 必须保留 lastPrivateSpaceBlockSignature / lastPrivateSpaceBlockAt 写入
        assertTrue(
            "活跃 AddApp 路径必须保留 lastPrivateSpaceBlockSignature 写入",
            body.contains("lastPrivateSpaceBlockSignature")
        )
        assertTrue(
            "活跃 AddApp 路径必须保留 lastPrivateSpaceBlockAt 写入",
            body.contains("lastPrivateSpaceBlockAt")
        )
    }

    /**
     * TC8: A12 反向契约 - 1200ms 去抖 + sample reset + caller reason 仍完整.
     */
    @Test
    fun activeAddAppBody_preservesDebounceAndSampleReset() {
        val src = readAntiUninstallSource()

        val bodyStart = src.indexOf("fun shouldBlockPrivateSpaceAddApp(")
        var braceCount = 0
        var bodyEnd = -1
        var foundOpen = false
        var i = bodyStart
        while (i < src.length) {
            val c = src[i]
            if (c == '{') {
                braceCount++
                foundOpen = true
            } else if (c == '}') {
                braceCount--
                if (foundOpen && braceCount == 0) {
                    bodyEnd = i
                    break
                }
            }
            i++
        }
        val body = src.substring(bodyStart, bodyEnd)

        // sample reset 必须保留 (作为闸 1 入口的副作用)
        assertTrue(
            "闸 1 入口的 sample reset 必须保留 (lastPrivateSpaceMatchedSample = null)",
            body.contains("lastPrivateSpaceMatchedSample = null")
        )
    }

    /**
     * TC9: A12 反向契约 - caller (check() 函数体) 的 reason chain 仍存在 PRIVATE_SPACE_ADD_APP.
     */
    @Test
    fun callerCheck_stillReferencesPrivateSpaceAddAppReason() {
        val src = readAntiUninstallSource()
        // check() 中 reason = "PRIVATE_SPACE_ADD_APP" 必须仍在
        // 不限定位置 (caller / logBlock 调用), 但 reason 字符串必须出现
        val checkStart = src.indexOf("fun check(")
        assertTrue("check() 函数必须存在", checkStart >= 0)

        // 找 check 函数体范围 (粗略, 但够用)
        var braceCount = 0
        var checkEnd = -1
        var foundOpen = false
        var j = checkStart
        while (j < src.length) {
            val c = src[j]
            if (c == '{') {
                braceCount++
                foundOpen = true
            } else if (c == '}') {
                braceCount--
                if (foundOpen && braceCount == 0) {
                    checkEnd = j
                    break
                }
            }
            j++
        }
        val checkBody = src.substring(checkStart, checkEnd)

        assertTrue(
            "caller check() 中必须仍出现 PRIVATE_SPACE_ADD_APP reason",
            checkBody.contains("PRIVATE_SPACE_ADD_APP")
        )
        assertTrue(
            "caller check() 中必须仍调用 shouldBlockPrivateSpaceAddApp",
            checkBody.contains("shouldBlockPrivateSpaceAddApp(")
        )
        assertTrue(
            "caller check() 中必须仍调用 refreshPrivateSpaceSession",
            checkBody.contains("refreshPrivateSpaceSession(")
        )
    }

    // =========================================================================
    // 测试工具
    // =========================================================================

    private fun newProtection(): AntiUninstallProtection =
        AntiUninstallProtection(context)

    /**
     * 直接调用 shouldBlockPrivateSpaceAddApp 之前需先激活 session,
     * 否则 isPrivateSpaceSessionActive(now) 返回 false, 函数提前返回.
     * 通过反射把 privateSpaceSessionUntil 推到未来, 模拟 refreshPrivateSpaceSession 已执行.
     */
    private fun activatePrivateSpaceSession(target: AntiUninstallProtection) {
        val field = findPrivateField(target, "privateSpaceSessionUntil")
        field.setLong(target, System.currentTimeMillis() + 60_000L)
        // 清空 page context (防止前一个测试残留 armed state)
        writePrivateObject(target, "privateSpaceAddAppPageUntil", 0L)
        writePrivateObject(target, "privateSpaceAddAppPagePkg", null)
        writePrivateObject(target, "privateSpaceAddAppPageRootPkg", null)
        writePrivateObject(target, "lastPrivateSpaceBlockAt", 0L)
        writePrivateObject(target, "lastPrivateSpaceBlockSignature", null)
        writePrivateObject(target, "lastPrivateSpaceMatchedSample", null)
    }

    private fun findPrivateField(target: Any, name: String): Field {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("No field $name on ${target.javaClass.name}")
    }

    private fun writePrivateObject(target: Any, name: String, value: Any?) {
        val f = findPrivateField(target, name)
        f.set(target, value)
    }

    /** 通过反射调用 private fun shouldBlockPrivateSpaceAddApp */
    private fun invokeShouldBlockPrivateSpaceAddApp(
        target: AntiUninstallProtection,
        currentPackage: String,
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo
    ): Boolean {
        val method = target.javaClass.declaredMethods.firstOrNull { m ->
            m.name == "shouldBlockPrivateSpaceAddApp" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == AccessibilityEvent::class.java &&
                m.parameterTypes[2] == AccessibilityNodeInfo::class.java
        } ?: throw AssertionError("shouldBlockPrivateSpaceAddApp(currentPackage, event, root) not found")
        method.isAccessible = true
        return method.invoke(target, currentPackage, event, root) as Boolean
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

    /** 反射读取 AntiUninstallProtection 私有字段 (Object 类型) */
    private fun readPrivateObject(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("No field $name on ${target.javaClass.name}")
    }

    /**
     * 构造 vivo 隐私空间的 "添加应用" 页面 root 节点, 但子节点不包含 IronWill.
     * 用于验证闸 1 命中即可拦截 (不依赖被保护应用文本).
     */
    private fun buildAddAppRootNodeWithoutIronWill(): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.packageName = "com.vivo.abe"
        root.className = "android.widget.ListView"
        root.text = "添加应用"

        // 子节点: 只有非 IronWill 应用 (微信 / QQ / 抖音)
        val otherApps = listOf("微信", "QQ", "抖音")
        otherApps.forEach { appName ->
            val child = AccessibilityNodeInfo.obtain()
            child.packageName = "com.vivo.abe"
            child.className = "android.widget.TextView"
            child.text = appName
            ImmediateBlockShadowAddChild.addChild(root, child)
            child.recycle()
        }

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
        text: String = "",
        className: String = "com.vivo.privacy.AppMoveInActivity"
    ): AccessibilityEvent {
        val event = AccessibilityEvent.obtain()
        event.eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        event.packageName = currentPackage
        event.className = className
        return event
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

    /** 读取 AntiUninstallProtection.kt 源码 (用于源码字符串扫描断言) */
    private fun readAntiUninstallSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").exists() && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Cannot locate project root" }
        val file = File(dir, "app/src/main/java/com/zhizhi/app/protection/AntiUninstallProtection.kt")
        require(file.exists()) { "AntiUninstallProtection.kt not found at ${file.absolutePath}" }
        return file.readText(Charsets.UTF_8)
    }
}

/**
 * 简化的 ShadowAccessibilityNodeInfo 反射访问封装,
 * 避免直接 import Robolectric 内部类污染测试代码。
 * 命名加 ImmediateBlock 前缀避免与 PrivacySpaceGuardEnforcementTest 冲突.
 */
private object ImmediateBlockShadowAccessibilityNodeInfoReset {
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

private object ImmediateBlockShadowAddChild {
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

/**
 * 简化封装: 用同一 JVM 进程跨测试做隔离标记,
 * 配合每测试 newProtection() 与 activatePrivateSpaceSession() 实现真正的状态隔离.
 */
private object ImmediateBlockShadowState {
    fun resetTrackedSignatures() {
        // 当前通过每个测试 newProtection() + activatePrivateSpaceSession() 内部清零实现隔离.
        // 占位方法, 留作未来扩展.
    }
}