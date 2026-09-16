package com.decikeeper.app.governance

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiMasterLayoutContractTest {

    private fun layout(name: String): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, "app/src/main/res/layout/$name"),
            File(cwd, "src/main/res/layout/$name"),
            File(cwd.parentFile ?: cwd, "app/src/main/res/layout/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Cannot locate layout $name from ${cwd.absolutePath}")
        return file.readText()
    }

    @Test
    fun lockScreen_keepsThreePrimaryActionsInVisualOrderAndUsesLightSurface() {
        val xml = layout("window_lock_screen.xml")

        val home = xml.indexOf("@+id/btn_back_to_home")
        val contextAction = xml.indexOf("@+id/btn_emergency")
        val privilege = xml.indexOf("@+id/btn_weekly_privilege")

        assertTrue("return desktop button must exist", home >= 0)
        assertTrue("context action button must exist", contextAction >= 0)
        assertTrue("weekly privilege button must exist", privilege >= 0)
        assertTrue("primary action order must be desktop -> context action -> weekly privilege", home < contextAction && contextAction < privilege)
        assertTrue("lock screen should use the seasonal light page surface", xml.contains("?attr/zzPageBg"))
    }

    @Test
    fun lockScreen_keepsStatusQuotaAndCenteredBorrowOverlay() {
        val xml = layout("window_lock_screen.xml")
        listOf(
            "@+id/tv_timer",
            "@+id/tv_sub_status",
            "@+id/layout_quota_bubble",
            "@+id/tv_current_segment_range",
            "@+id/tv_stat_time_big",
            "@+id/tv_usable_count_big",
            "@+id/tv_stat_time_detail",
            "@+id/tv_surplus_borrow_line",
            "@+id/tv_rest_countdown_in_card",
            "@+id/tv_next_segment_info",
            "@+id/view_borrow_confirm_overlay",
            "@+id/tv_borrow_confirm_title",
            "@+id/tv_borrow_confirm_message",
            "@+id/btn_borrow_confirm_ok",
            "@+id/btn_borrow_confirm_cancel"
        ).forEach { id -> assertTrue("missing $id", xml.contains(id)) }
        assertTrue("borrow confirmation must remain centered", xml.contains("android:layout_gravity=\"center\""))
    }

    @Test
    fun restRequest_keepsCompleteCenteredInputFlow() {
        val xml = layout("window_rest_request.xml")
        listOf(
            "@+id/tv_title",
            "@+id/tv_rest_tier_hint",
            "@+id/tv_quota_hint",
            "@+id/tv_cooling_penalty_hint",
            "@+id/tv_deduction_detail",
            "@+id/til_input",
            "@+id/tv_calm_minutes",
            "@+id/btn_cancel_emergency",
            "@+id/btn_confirm_calm",
            "@+id/btn_stop_free_time"
        ).forEach { id -> assertTrue("missing $id", xml.contains(id)) }
        assertTrue(xml.contains("android:gravity=\"center\""))
    }

    @Test
    fun appSelection_keepsSearchTwoColumnsAndSegmentTools() {
        val xml = layout("activity_app_selection.xml")
        listOf(
            "@+id/et_search_apps",
            "@+id/list_all_apps",
            "@+id/list_selected_apps",
            "@+id/tv_selected_title",
            "@+id/ll_segment_tools",
            "@+id/btn_select_all",
            "@+id/btn_invert",
            "@+id/btn_restore_global",
            "@+id/btn_quick_import",
            "@+id/btn_save_apps"
        ).forEach { id -> assertTrue("missing $id", xml.contains(id)) }
    }

    @Test
    fun alarmEdit_keepsTwoStageFreeMultiSelectAndCounts() {
        val xml = layout("dialog_alarm_edit.xml")
        listOf(
            "@+id/layout_pick_alarm_time",
            "@+id/tv_alarm_edit_time",
            "@+id/rg_unlock_mode",
            "@+id/rb_unlock_single",
            "@+id/rb_unlock_two_stage",
            "@+id/layout_stage1",
            "@+id/layout_stage2",
            "@+id/cb_s1_qr",
            "@+id/cb_s1_math",
            "@+id/cb_s1_poem",
            "@+id/cb_s1_word",
            "@+id/cb_s1_cloze",
            "@+id/cb_s2_qr",
            "@+id/cb_s2_math",
            "@+id/cb_s2_poem",
            "@+id/cb_s2_word",
            "@+id/cb_s2_cloze",
            "@+id/et_math_count",
            "@+id/et_poem_count",
            "@+id/et_word_count",
            "@+id/et_cloze_count"
        ).forEach { id -> assertTrue("missing $id", xml.contains(id)) }
    }
}
