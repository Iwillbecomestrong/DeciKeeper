package com.decikeeper.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesSettingsUiContractTest {

    @Test
    fun settingsPageUsesApprovedProtectionHierarchy() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")

        assertTrue(xml.indexOf("android:text=\"无障碍服务\"") < xml.indexOf("@string/settings_protection_section_title"))
        assertTrue(
            xml.indexOf("@string/settings_protection_alarm_title") <
                xml.indexOf("android:text=\"桌面守护小浮窗\"")
        )
        assertTrue(
            xml.indexOf("android:text=\"桌面守护小浮窗\"") <
                xml.indexOf("@string/settings_protection_floating_title")
        )
        assertTrue(
            xml.indexOf("@string/settings_protection_floating_title") <
                xml.indexOf("@string/settings_protection_privacy_title")
        )
        assertTrue(
            xml.indexOf("@string/settings_protection_privacy_title") <
                xml.indexOf("@string/settings_protection_uninstall_perm_title")
        )
        assertTrue(
            xml.indexOf("@string/settings_protection_uninstall_perm_title") <
                xml.indexOf("@string/settings_protection_dev_advanced_title")
        )
        // 开发者进阶拦截（安装拦截 + 开发者选项拦截合并入口）是列表最后一项，排在 OPPO 专享的设备管理器行之后。
        assertTrue(
            xml.indexOf("@+id/switch_device_admin_protection") <
                xml.indexOf("@+id/row_dev_advanced_guard")
        )
        // 两项拦截的开关不再占列表行，改在弹窗里管理。
        assertFalse(xml.contains("@+id/switch_developer_options_guard"))
        assertFalse(xml.contains("@+id/switch_install_guard"))
        // 日期与时间拦截是「加强约束功能」区块的第一项，排在闹钟防关机保护之前。
        assertTrue(
            xml.indexOf("@string/settings_protection_section_title") <
                xml.indexOf("@+id/switch_date_time_guard")
        )
        assertTrue(
            xml.indexOf("@+id/switch_date_time_guard") <
                xml.indexOf("@string/settings_protection_alarm_title")
        )
        assertFalse(xml.contains("查看日志"))
        assertEquals(1, Regex("android:text=\"导出日志\"").findAll(xml).count())
    }

    @Test
    fun devAdvancedGuardDialogOwnsBothGuardsAndSharedExemptionWindow() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showDevAdvancedGuardDialog")
        val end = source.indexOf("private fun setupDateTimeGuardSwitch", start)
        val dialog = source.substring(start, end)

        assertTrue(dialog.contains("setupInstallGuardSwitch(sw)"))
        assertTrue(dialog.contains("setupDeveloperOptionsGuardSwitch(sw)"))
        assertTrue(dialog.contains("豁免时段内放行"))
        assertTrue(dialog.contains("DevGuardExemptionManager.setExemptionEnabled(ctx, guard, checked)"))
        assertTrue(dialog.contains("DevGuardExemptionManager.setWindow("))
        assertTrue(dialog.contains("共享豁免时段"))
        // 弹窗里不放演示 / 调试控件。
        assertFalse(dialog.contains("模拟"))
        // 日期与时间拦截不参与豁免。
        assertFalse(dialog.contains("DateTimeGuardManager"))
    }

    @Test
    fun dateTimeGuardFollowsAccessibilityCloseFlow() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun submitProtectionDisablesForAccessibility")
        val end = source.indexOf("private fun showA11yDisableConfirmDialog", start)
        assertTrue(source.substring(start, end).contains("DateTimeGuardManager.setProtectionEnabled(ctx, false)"))

        assertEquals(2, source.split("日期与时间拦截 → ").size - 1)
        assertEquals(2, source.split("!TimeRuleManager.getBaselineDateTimeGuardState(ctx) &&").size - 1)
    }

    @Test
    fun developerOptionsGuardFollowsAccessibilityCloseFlow() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun submitProtectionDisablesForAccessibility")
        val end = source.indexOf("private fun showA11yDisableConfirmDialog", start)
        assertTrue(source.substring(start, end).contains("DeveloperOptionsGuardManager.setProtectionEnabled(ctx, false)"))

        // 两个「关闭无障碍」确认弹窗都要列出该项，并把它计入「今日基准全为关闭」的立即解除判定。
        assertEquals(2, source.split("开发者选项拦截 → ").size - 1)
        assertEquals(2, source.split("!TimeRuleManager.getBaselineDeveloperOptionsGuardState(ctx) &&").size - 1)

        // 安装拦截同样并入协同关闭与两个确认弹窗。
        assertTrue(source.substring(start, end).contains("InstallGuardManager.setProtectionEnabled(ctx, false)"))
        assertEquals(2, source.split("安装拦截 → ").size - 1)
        assertEquals(2, source.split("!TimeRuleManager.getBaselineInstallGuardState(ctx) &&").size - 1)
    }

    @Test
    fun protectionSectionUsesUnifiedHelpAndDropsChildHelpButtons() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")

        // Section title header owns unified help button
        assertTrue("fragment_rules.xml must contain btn_protection_section_help", xml.contains("@+id/btn_protection_section_help"))
        assertTrue("RulesFragment must bind setupProtectionSectionHelp", source.contains("setupProtectionSectionHelp"))

        // Individual redundant help buttons must NOT exist
        val droppedHelpButtons = listOf(
            "btn_alarm_power_off_help",
            "btn_floating_window_protection_help",
            "btn_privacy_space_guard_help",
            "btn_uninstall_and_perm_protection_help",
            "btn_floating_guard_help",
            "btn_device_admin_protection_help",
            "btn_accessibility_service_help",
            "btn_edit_window_policy_help"
        )
        droppedHelpButtons.forEach { helpId ->
            assertFalse("fragment_rules.xml should not contain $helpId", xml.contains("@+id/$helpId"))
        }
    }

    @Test
    fun protectionRowsUseUnifiedNamesAndStatusContract() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")

        assertFalse(xml.contains("switch_accessibility\""))
        assertFalse(xml.contains("switch_uninstall_protection\""))
        assertTrue(source.contains("showLockScreenMissingWarningDialog"))
        assertTrue(source.contains("不开锁屏，仍要开启"))
        assertTrue(source.contains("disableUninstallAndPermProtection(ctx)"))
        assertFalse(source.contains("showUninstallAndPermDisableDialog"))
        assertTrue(source.contains("状态: 已开启"))
        assertTrue(source.contains("状态: 未开启"))
        assertTrue(source.contains("状态: 待关闭（明日"))
    }

    @Test
    fun settingsPageOwnsEditWindowPolicyAboveProtectionSection() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")

        assertTrue(xml.contains("@+id/card_edit_window_policy"))
        assertTrue(xml.contains("@+id/rg_settings_edit_window_policy"))
        assertTrue(xml.contains("@+id/rb_settings_edit_window_all_day"))
        assertTrue(xml.contains("@+id/rb_settings_edit_window_effective_to_midnight"))

        val policyIndex = xml.indexOf("@+id/card_edit_window_policy")
        val protectionIndex = xml.indexOf("@string/settings_protection_section_title")
        val themeIndex = xml.indexOf("showThemePicker")
        val a11yIndex = xml.indexOf("switch_accessibility_service")
        assertTrue("card_edit_window_policy must be above protection section", policyIndex < protectionIndex)
        assertTrue("card_edit_window_policy must be below theme card", themeIndex < policyIndex)
        assertTrue("card_edit_window_policy must be above accessibility service", policyIndex < a11yIndex)

        // Controller bindings and logic
        assertTrue(source.contains("TimeRuleEditWindowPolicyManager.isEditLocked"))
        assertTrue(source.contains("TimeRuleEditWindowPolicyManager.applyPolicyChange"))
        assertTrue(source.contains("updateEditWindowPolicyUI"))
        assertTrue(source.contains("val displayPolicy = pendingPolicy ?: activePolicy"))
    }

    @Test
    fun recentTasksHideCardIsRemovedAndExportLogRemains() {
        val xml = source("app/src/main/res/layout/fragment_rules.xml")
        assertFalse(xml.contains("btn_hide_in_recent_tasks_help"))
        assertFalse(xml.contains("switch_hide_in_recent_tasks"))
        assertTrue(xml.contains("btn_quick_send_logs"))
    }

    @Test
    fun accessibilityGuideDoesNotOwnBackgroundBatteryRowNorRecentTasksHide() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAccessibilityServiceGuideDialog()")
        val end = source.indexOf("private fun setupAlarmPowerOffProtectionSwitch", start)
        val guide = source.substring(start, end)

        assertFalse(guide.contains("btnBgBattery"))
        assertFalse(guide.contains("后台耗电"))
        assertFalse(guide.contains("hideRecentsOk"))
        assertFalse(guide.contains("rowHideRecents"))
        assertFalse(guide.contains("隐藏多任务卡片"))
        assertTrue(guide.contains("ASSOC_START_CONFIRMED_KEY"))
    }

    @Test
    fun homePermissionGuideKeepsBatteryBeforeAutoStartAndDropsUsageAndBackgroundPopup() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/HomeFragment.kt")
        assertFalse(source.contains("addItem(\"使用情况访问权限\""))
        assertFalse(source.contains("name = \"后台弹出界面权限\""))
        val batteryIndex = listOf("addItem(\"后台耗电管理\"", "addItem(bgBatteryLabel").map { source.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
        val autoStartIndex = listOf("name = \"自启动权限\"", "name = autostartLabel").map { source.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
        assertTrue(batteryIndex in 0 until autoStartIndex)
    }

    @Test
    fun timeRulesPageOwnsGlobalBlacklistEntry() {
        val timeLayout = source("app/src/main/res/layout/fragment_time_config.xml")
        val settings = source("app/src/main/res/layout/fragment_rules.xml")
        assertTrue(timeLayout.contains("@+id/btn_global_blacklist"))
        assertFalse(settings.contains("全局黑名单"))
    }

    @Test
    fun timeRulesExposeGlobalParamsAsThirdTab() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/FocusRulesFragment.kt")
        val settings = source("app/src/main/res/layout/fragment_rules.xml")

        assertTrue(source.contains("const val TAB_GLOBAL_PARAMS = 2"))
        assertTrue(source.contains("setText(\"规则参数\")"))
        assertTrue(source.contains("container_global_params"))
        assertTrue(source.contains("GlobalParamsFragment"))
        assertFalse(settings.contains("全局参数"))
        assertFalse(settings.contains("规则参数"))
    }

    @Test
    fun globalParamsStartAtSameTopInsetAsOtherTimeRulePages() {
        val layout = source("app/src/main/res/layout/activity_global_params_new.xml")
        val fragmentSource = source("app/src/main/java/com/decikeeper/app/ui/GlobalParamsFragment.kt")
        assertTrue(layout.contains("android:paddingTop=\"0dp\""))
        assertTrue(layout.contains("规则参数设置"))
        assertTrue(layout.contains("配置特权、时间范围与生效策略等全局运行参数。"))
        assertTrue(layout.contains("android:id=\"@+id/btn_save\""))
        assertTrue(layout.contains("android:visibility=\"gone\""))
        assertFalse(fragmentSource.contains("setOnApplyWindowInsetsListener"))
        assertTrue(fragmentSource.contains("setupAutoSave"))
        assertTrue(fragmentSource.contains("performSave"))
    }

    @Test
    fun alarmGuideOnlyOffersLockScreenActionAfterAccessibilityGate() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAlarmPowerOffProtectionGuideDialog()")
        val end = source.indexOf("private fun showLockScreenMissingWarningDialog()", start)
        val guide = source.substring(start, end)

        assertTrue(guide.contains("去开启锁屏显示"))
        assertFalse(guide.contains("btnA11y"))
        assertFalse(guide.contains("cbA11y"))
        assertFalse(guide.contains("openAccessibilitySettings()"))
    }

    @Test
    fun alarmGuideUsesHorizontalPermissionMatrixLikeAccessibilityGuide() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val start = source.indexOf("private fun showAlarmPowerOffProtectionGuideDialog()")
        val end = source.indexOf("private fun showLockScreenMissingWarningDialog()", start)
        val guide = source.substring(start, end)

        assertTrue(guide.contains("orientation = LinearLayout.HORIZONTAL"))
        assertTrue(guide.contains("gravity = Gravity.CENTER_VERTICAL"))
        assertTrue(guide.contains("LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)"))
        assertTrue(guide.contains("lockScreenRow.addView(btnLockScreen)"))
        assertTrue(guide.contains("lockScreenRow.addView(cbLockScreen)"))
    }

    @Test
    fun alarmPermissionGuideRefreshesAfterReturningFromSystemSettings() {
        val source = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        assertTrue(source.contains("alarmPrerequisiteDialogRefresh?.invoke()"))
        assertTrue(source.contains("alarmPrerequisiteDialogRefresh = { updateDialogUi() }"))
    }

    @Test
    fun refreshTimeSyncFollowsEarliestEndingSegmentInAllowedWindow() {
        val midnight = com.decikeeper.app.data.TimeSegment(23, 0, 7, 30, 10, "深夜")
        val morning = com.decikeeper.app.data.TimeSegment(8, 15, 12, 0, 30, "上午")
        val afternoon = com.decikeeper.app.data.TimeSegment(12, 45, 16, 45, 35, "下午")
        val evening = com.decikeeper.app.data.TimeSegment(17, 30, 22, 0, 35, "晚上")

        val segments = listOf(morning, afternoon, evening, midnight)
        val syncTime = com.decikeeper.app.manager.TimeRuleManager.findRefreshTimeFromSegments(segments)
        org.junit.Assert.assertEquals("07:30", syncTime)

        val rulesFragmentSource = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        assertTrue(rulesFragmentSource.contains("findRefreshTimeFromSegments"))
        assertFalse(rulesFragmentSource.contains("firstSeg.startHour, firstSeg.startMinute"))

        val globalParamsFragmentSource = source("app/src/main/java/com/decikeeper/app/ui/GlobalParamsFragment.kt")
        assertFalse(globalParamsFragmentSource.contains("et_refresh_time"))
        assertFalse(globalParamsFragmentSource.contains("switch_refresh_sync"))

        val globalParamsActivitySource = source("app/src/main/java/com/decikeeper/app/ui/GlobalParamsActivity.kt")
        assertTrue(globalParamsActivitySource.contains("findRefreshTimeFromSegments"))
        assertFalse(globalParamsActivitySource.contains("firstSeg.startHour, firstSeg.startMinute"))
    }

    @Test
    fun basicPermissionsCardNavigatesToPermissionsActivity() {
        val rulesSource = source("app/src/main/java/com/decikeeper/app/ui/RulesFragment.kt")
        val rulesXml = source("app/src/main/res/layout/fragment_rules.xml")
        assertTrue(rulesXml.contains("@+id/card_basic_permissions"))
        assertTrue(rulesSource.contains("PermissionsActivity::class.java"))

        val permActivitySource = source("app/src/main/java/com/decikeeper/app/ui/PermissionsActivity.kt")
        assertTrue(permActivitySource.contains("ThemeManager.apply(this)"))
        assertTrue(permActivitySource.contains("一、基础运行（日常拦截与常驻保活）"))
        assertTrue(permActivitySource.contains("二、挑战闹钟（防漏响与起床核验）"))
        assertTrue(permActivitySource.contains("三、时间规则（应用使用识别）"))
        assertTrue(permActivitySource.contains("四、增强能力（高级防绕过，按需开启）"))

        // 契约：自启动、关联启动等系统无法识别的权限使用 CheckBox 矩阵框
        assertTrue(permActivitySource.contains("PermItem.Manual"))
        assertTrue(permActivitySource.contains("val checkBox = CheckBox(ctx)"))
        assertTrue(permActivitySource.contains("auto_start_confirmed"))
        assertTrue(permActivitySource.contains("assoc_start_confirmed"))
        assertTrue(permActivitySource.contains("recents_lock_confirmed"))

        // 契约：删掉精确闹钟权限（应用声明了 USE_EXACT_ALARM，无系统独立开关）
        assertFalse(permActivitySource.contains("精确闹钟权限"))

        // 契约：设备管理器仅在适配推荐机型（如 OPPO）展示，vivo 等机型默认隐藏
        assertTrue(permActivitySource.contains("isDeviceAdminRecommended"))

        // 契约：设置页弹窗的基础权限按钮直接导航至 PermissionsActivity，不再切回主页
        assertFalse(rulesSource.contains("selectedItemId = R.id.nav_home"))

        // 契约：统一标题为「基础权限与系统设置」
        val permXml = source("app/src/main/res/layout/activity_permissions.xml")
        assertTrue(permXml.contains("基础权限与系统设置"))
    }

    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, path),
            File(cwd, path.removePrefix("app/")),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, path.removePrefix("app/"))
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
}
