package com.decikeeper.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTodayUsageExclusionTest {

    @Test
    fun testTransientTodayLedgerKeyFiltersTodayUsageAndState() {
        val today = "20260912"

        // 今日瞬态消耗必须被过滤
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("usage_${today}_morning", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("borrow_time_${today}_morning", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("count_usage_${today}_morning", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("count_usage_half_units_v2_${today}_morning", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("privilege_usage_${today}_morning", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("exit_${today}_morning", today))

        // 运行中即时状态必须被过滤
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("free_time_end", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("cool_down_end_timestamp", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("is_privilege_mode", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("privilege_session_start", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("privilege_session_duration", today))
        assertTrue(SettingsBackupManager.isTransientTodayLedgerKey("record_date_str", today))

        // 历史以往日期的消耗键应保留供统计归档
        assertFalse(SettingsBackupManager.isTransientTodayLedgerKey("usage_20260911_morning", today))
        assertFalse(SettingsBackupManager.isTransientTodayLedgerKey("borrow_time_20260910_work", today))

        // 长期跨天配置必须保留
        assertFalse(SettingsBackupManager.isTransientTodayLedgerKey("weekly_privilege_duration_limit_v2", today))
        assertFalse(SettingsBackupManager.isTransientTodayLedgerKey("weekly_privilege_count_limit_v2", today))
        assertFalse(SettingsBackupManager.isTransientTodayLedgerKey("blacklist_apps_list", today))
    }
}
