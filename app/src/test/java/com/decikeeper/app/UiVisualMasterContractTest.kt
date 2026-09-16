package com.decikeeper.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiVisualMasterContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path), File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path), File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }
    private fun layout(name: String) = source("src/main/res/layout/$name")

    @Test fun primaryScreensUseSeasonalTokensInsteadOfLegacySeaPalette() {
        listOf("fragment_home.xml","fragment_rules.xml","fragment_statistics.xml","fragment_focus_rules.xml","fragment_time_config.xml","fragment_app_window.xml","activity_global_params_new.xml","fragment_alarm.xml","activity_app_selection.xml","window_lock_screen.xml","window_rest_request.xml").forEach { name ->
            val xml = layout(name)
            assertFalse("$name must not use legacy sea-gradient background", xml.contains("@drawable/bg_sea_surface"))
            assertTrue("$name must use seasonal theme attributes", xml.contains("?attr/zz"))
        }
    }

    @Test fun secondaryScreensAlsoFollowTheSeasonalMaster() {
        listOf("activity_bookmark.xml","activity_schedule_preview.xml","activity_app_window_preview.xml","activity_about.xml","activity_legal_document.xml","activity_privacy_consent.xml").forEach { name ->
            val xml = layout(name)
            assertTrue("$name must use seasonal theme attributes", xml.contains("?attr/zz"))
            assertFalse("$name must not keep old cyan/blue chrome", xml.contains("#00838F") || xml.contains("#006064") || xml.contains("#0D47A1"))
        }
    }

    @Test fun bookmarkDynamicUiUsesThemeTokensInsteadOfLegacyCyanPalette() {
        val kt = source("src/main/java/com/decikeeper/app/ui/BookmarkActivity.kt")
        assertTrue(kt.contains("ThemeManager.apply(this)"))
        listOf("#00838F", "#00695C", "#004D40", "#E0F2F1", "#90A4AE").forEach { assertFalse(kt.contains(it)) }
    }

    @Test fun normalSecondaryActivitiesApplyTheSelectedSeason() {
        listOf("SchedulePreviewActivity.kt","AppWindowPreviewActivity.kt","AppSelectionActivity.kt","GlobalParamsActivity.kt","BookmarkActivity.kt","AboutActivity.kt","LegalDocumentActivity.kt").forEach { name ->
            val kt = source("src/main/java/com/decikeeper/app/ui/$name")
            assertTrue("$name must apply selected season before rendering", kt.contains("ThemeManager.apply(this)"))
        }
    }

    @Test fun dynamicHelpScreenIsRethemedAfterItsProgrammaticLayoutIsBuilt() {
        val provider = source("src/main/java/com/decikeeper/app/ui/AlarmEmergencyWhitelistUiProvider.kt")
        val helper = source("src/main/java/com/decikeeper/app/ui/SeasonalRuntimeRethemer.kt")
        assertTrue(provider.contains("SeasonalRuntimeRethemer.apply(activity)"))
        assertTrue(helper.contains("activity is HelpActivity"))
        listOf("R.attr.zzPageBg","R.attr.zzSurface","R.attr.zzText","R.attr.zzAccentStrong","R.attr.zzMuted","R.attr.zzLine").forEach {
            assertTrue("dynamic Help theme missing $it", helper.contains(it))
        }
    }

    @Test fun statisticsRuntimeUsesApprovedTerminologyAndSeasonalPalette() {
        val kt = source("src/main/java/com/decikeeper/app/ui/StatisticsFragment.kt")
        assertFalse(kt.contains("自律"))
        listOf("#00838F", "#4CAF50", "#2196F3", "#9C27B0", "#FF9800").forEach { assertFalse(kt.contains(it)) }
        assertTrue(kt.contains("R.attr.zzAccent"))
    }

    @Test fun homeBorrowStatusIsRethemedAtRuntime() {
        val helper = source("src/main/java/com/decikeeper/app/ui/SeasonalRuntimeRethemer.kt")
        assertTrue(helper.contains("R.id.tv_surplus_borrow_line"))
        assertTrue(helper.contains("R.attr.zzWarning"))
        assertTrue(helper.contains("0xFFFFB74D"))
        assertTrue(helper.contains("OnPreDrawListener"))
    }

    @Test fun statisticsMatchesApprovedProductDecisions() {
        val xml = layout("fragment_statistics.xml")
        assertTrue(xml.contains("勿扰时长")); assertFalse(xml.contains("自律时长"))
        assertTrue(xml.contains("@+id/cb_weighted_stats") && xml.contains("android:visibility=\"gone\""))
        assertTrue(xml.contains("@+id/btn_show_conversion")); assertFalse(xml.contains("android:text=\"换算表\""))
    }

    @Test fun settingsContainsThemeEntryAndNoWheelchairIcon() {
        val xml = layout("fragment_rules.xml")
        assertTrue(xml.contains("主题")); assertTrue(xml.contains("com.decikeeper.app.ui.ThemeSummaryView"))
        assertFalse(xml.contains("♿")); assertTrue(xml.contains("无障碍服务"))
        assertTrue(xml.contains("@+id/btn_open_help")); assertTrue(xml.contains("@+id/btn_open_about"))
    }

    @Test fun homeKeepsSetupGuideQuotaTodayRulesAndWeeklyPrivilege() {
        val xml = layout("fragment_home.xml")
        listOf("@+id/card_setup_guide","@+id/layout_quota_bubble","com.decikeeper.app.ui.TodayRulesView","@+id/card_today_alarm","com.decikeeper.app.ui.TodayAlarmView","@+id/card_weekly_privilege_entry").forEach { assertTrue(xml.contains(it)) }
        assertFalse(xml.contains("@+id/btn_help"))
    }

    @Test fun focusRulesAndGlobalParamsUseRuleParamsTerminology() {
        val focusRules = source("src/main/java/com/decikeeper/app/ui/FocusRulesFragment.kt")
        assertTrue(focusRules.contains("\"规则参数\""))
        assertFalse(focusRules.contains("\"全局参数\""))

        val globalParamsXml = layout("activity_global_params_new.xml")
        assertTrue(globalParamsXml.contains("规则参数"))
    }

    @Test fun bottomNavigationUsesFiveMatchingCustomIcons() {
        val menu = source("src/main/res/menu/bottom_nav_menu.xml")
        listOf("ic_nav_home","ic_nav_focus","ic_nav_stats","ic_nav_alarm","ic_nav_settings").forEach { assertTrue(menu.contains("@drawable/$it")) }
        assertFalse(menu.contains("@android:drawable"))
    }

    @Test fun bottomNavigationItemOrderMatchesProductContract() {
        val menu = source("src/main/res/menu/bottom_nav_menu.xml")
        val homeIdx = menu.indexOf("@+id/nav_home")
        val timeIdx = menu.indexOf("@+id/nav_time_config")
        val alarmIdx = menu.indexOf("@+id/nav_alarm")
        val statsIdx = menu.indexOf("@+id/nav_statistics")
        val rulesIdx = menu.indexOf("@+id/nav_rules")

        assertTrue("nav_home is first", homeIdx in 0 until timeIdx)
        assertTrue("nav_time_config is second", timeIdx < alarmIdx)
        assertTrue("nav_alarm is centered third", alarmIdx < statsIdx)
        assertTrue("nav_statistics is fourth", statsIdx < rulesIdx)
        assertTrue("nav_rules is fifth", rulesIdx > statsIdx)
    }

    @Test fun lockScreenRemainsCompleteAndLight() {
        val xml = layout("window_lock_screen.xml")
        val home = xml.indexOf("@+id/btn_back_to_home"); val action = xml.indexOf("@+id/btn_emergency"); val privilege = xml.indexOf("@+id/btn_weekly_privilege")
        assertTrue(home >= 0 && action > home && privilege > action)
        assertTrue(xml.contains("@+id/tv_timer")); assertTrue(xml.contains("@+id/tv_sub_status")); assertTrue(xml.contains("@+id/view_borrow_confirm_overlay")); assertTrue(xml.contains("?attr/zzPageBg"))
    }
}
