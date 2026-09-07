package com.zhizhi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiVisualMasterContractTest {
    private fun source(path: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, path),
            File(cwd, "app/$path"),
            File(cwd.parentFile ?: cwd, path),
            File(cwd.parentFile ?: cwd, "app/$path")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Cannot locate $path from ${cwd.absolutePath}")
    }

    private fun layout(name: String) = source("src/main/res/layout/$name")

    @Test
    fun primaryScreensUseSeasonalTokensInsteadOfLegacySeaPalette() {
        listOf(
            "fragment_home.xml",
            "fragment_rules.xml",
            "fragment_statistics.xml",
            "fragment_focus_rules.xml",
            "fragment_time_config.xml",
            "fragment_app_window.xml",
            "activity_global_params_new.xml",
            "fragment_alarm.xml",
            "activity_app_selection.xml",
            "window_lock_screen.xml",
            "window_rest_request.xml"
        ).forEach { name ->
            val xml = layout(name)
            assertFalse("$name must not use the legacy sea-gradient background", xml.contains("@drawable/bg_sea_surface"))
            assertTrue("$name must use seasonal theme attributes", xml.contains("?attr/zz"))
        }
    }

    @Test
    fun secondaryScreensAlsoFollowTheSeasonalMaster() {
        listOf(
            "activity_bookmark.xml",
            "activity_schedule_preview.xml",
            "activity_app_window_preview.xml",
            "activity_about.xml",
            "activity_legal_document.xml",
            "activity_privacy_consent.xml"
        ).forEach { name ->
            val xml = layout(name)
            assertTrue("$name must use seasonal theme attributes", xml.contains("?attr/zz"))
            assertFalse("$name must not keep old cyan/blue page chrome", xml.contains("#00838F") || xml.contains("#006064") || xml.contains("#0D47A1"))
        }
    }

    @Test
    fun bookmarkDynamicUiUsesThemeTokensInsteadOfLegacyCyanPalette() {
        val kt = source("src/main/java/com/zhizhi/app/ui/BookmarkActivity.kt")
        assertTrue(kt.contains("ThemeManager.apply(this)"))
        listOf("#00838F", "#00695C", "#004D40", "#E0F2F1", "#90A4AE").forEach {
            assertFalse("BookmarkActivity must not hard-code legacy palette $it", kt.contains(it))
        }
    }

    @Test
    fun statisticsMatchesApprovedProductDecisions() {
        val xml = layout("fragment_statistics.xml")
        assertTrue(xml.contains("勿扰时长"))
        assertFalse(xml.contains("自律时长"))
        assertTrue(xml.contains("@+id/cb_weighted_stats") && xml.contains("android:visibility=\"gone\""))
        assertTrue(xml.contains("@+id/btn_show_conversion"))
        assertFalse("weighted/conversion controls are hidden product features", xml.contains("android:text=\"换算表\""))
    }

    @Test
    fun settingsContainsThemeEntryAndNoWheelchairIcon() {
        val xml = layout("fragment_rules.xml")
        assertTrue(xml.contains("主题"))
        assertTrue(xml.contains("com.zhizhi.app.ui.ThemeSummaryView"))
        assertFalse(xml.contains("♿"))
        assertTrue(xml.contains("无障碍服务"))
    }

    @Test
    fun homeKeepsSetupGuideQuotaTodayRulesAndWeeklyPrivilege() {
        val xml = layout("fragment_home.xml")
        listOf(
            "@+id/card_setup_guide",
            "@+id/layout_quota_bubble",
            "com.zhizhi.app.ui.TodayRulesView",
            "@+id/card_weekly_privilege_entry",
            "@+id/btn_help"
        ).forEach { assertTrue("fragment_home.xml missing $it", xml.contains(it)) }
    }

    @Test
    fun bottomNavigationUsesFiveMatchingCustomIcons() {
        val menu = source("src/main/res/menu/bottom_nav_menu.xml")
        listOf("ic_nav_home", "ic_nav_focus", "ic_nav_stats", "ic_nav_alarm", "ic_nav_settings")
            .forEach { assertTrue("bottom nav missing $it", menu.contains("@drawable/$it")) }
        assertFalse(menu.contains("@android:drawable"))
    }

    @Test
    fun lockScreenRemainsCompleteAndLight() {
        val xml = layout("window_lock_screen.xml")
        val home = xml.indexOf("@+id/btn_back_to_home")
        val action = xml.indexOf("@+id/btn_emergency")
        val privilege = xml.indexOf("@+id/btn_weekly_privilege")
        assertTrue(home >= 0 && action > home && privilege > action)
        assertTrue(xml.contains("@+id/tv_timer"))
        assertTrue(xml.contains("@+id/tv_sub_status"))
        assertTrue(xml.contains("@+id/view_borrow_confirm_overlay"))
        assertTrue(xml.contains("?attr/zzPageBg"))
    }
}
