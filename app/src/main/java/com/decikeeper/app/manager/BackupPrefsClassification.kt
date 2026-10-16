package com.decikeeper.app.manager

/**
 * 设置备份对每个 SharedPreferences 文件的处理决定。
 *
 * 新增 prefs 文件时必须在此登记（`BackupPrefsClassificationTest` 会扫描源码强制检查），
 * 避免“全部导入再排除”那样在新增文件时悄悄把运行状态带进导入。
 */
internal enum class BackupDecision {
    /** 属于某个备份分类，按该分类的文件/字段表导入。 */
    IMPORT,

    /** 导入时与本机状态合并，不整文件覆盖（只增不减的额度类数据）。 */
    MERGE,

    /** 权限确认或依赖无障碍/系统权限的状态：新环境需要重新授权与检测，不导入。 */
    SKIP_ACCESSIBILITY,

    /** 防卸载等“承诺状态”：恢复旧备份可能让已走完或已取消的流程重新生效，不导入。 */
    SKIP_SECURITY,

    /** 响铃/监控进行中的运行状态或设备相关标记：不导入。 */
    SKIP_RUNTIME,

    /** 合规要求（例如隐私同意）必须在新环境重新取得，不导入。 */
    SKIP_COMPLIANCE,

    /** 尚未逐项评审，暂不导入；评审后应改为上面某一类。 */
    PENDING_REVIEW
}

internal object BackupPrefsClassification {
    val decisions: Map<String, BackupDecision> = mapOf(
        // ── 导入：规则、黑名单、应用时间窗、闹钟、规则参数、统计 ──
        "decikeeper_rules" to BackupDecision.IMPORT,
        "decikeeper_rules_pending" to BackupDecision.IMPORT,
        "decikeeper_rules_baseline" to BackupDecision.IMPORT,
        "decikeeper_ledger" to BackupDecision.IMPORT,
        "decikeeper_pending_apps" to BackupDecision.IMPORT,
        "pending_config_prefs" to BackupDecision.IMPORT,
        "app_window_rules" to BackupDecision.IMPORT,
        "app_window_pending" to BackupDecision.IMPORT,
        "app_window_baseline" to BackupDecision.IMPORT,
        "alarm_prefs" to BackupDecision.IMPORT,
        "alarm_bookmarks" to BackupDecision.IMPORT,
        "alarm_challenge_emergency_whitelist" to BackupDecision.IMPORT,
        "alarm_stats_ledger" to BackupDecision.IMPORT,
        "rest_stats_ledger" to BackupDecision.IMPORT,
        "statistics_prefs" to BackupDecision.IMPORT,
        "statistics_retention_prefs" to BackupDecision.IMPORT,

        // ── 合并 ──
        "alarm_emergency_exit" to BackupDecision.MERGE,

        // ── 不导入：权限与依赖无障碍/系统权限的状态 ──
        "accessibility_control_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "monitor_a11y_health" to BackupDecision.SKIP_ACCESSIBILITY,
        "permission_monitor" to BackupDecision.SKIP_ACCESSIBILITY,
        "decikeeper_permissions" to BackupDecision.SKIP_ACCESSIBILITY,
        "decikeeper_oem_guide" to BackupDecision.SKIP_ACCESSIBILITY,
        "floating_window_protection_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "guard_floating_window_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "developer_options_guard_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "install_guard_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "date_time_guard_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "dev_guard_exemption_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "privacy_protection_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "alarm_power_off_protection_prefs" to BackupDecision.SKIP_ACCESSIBILITY,
        "pwa_bypass" to BackupDecision.SKIP_ACCESSIBILITY,

        // ── 不导入：防卸载承诺状态 ──
        "anti_uninstall_prefs" to BackupDecision.SKIP_SECURITY,

        // ── 不导入：合规 ──
        "privacy_consent_prefs" to BackupDecision.SKIP_COMPLIANCE,

        // ── 不导入：运行状态 / 设备相关 ──
        "alarm_bootstrap_prefs" to BackupDecision.SKIP_RUNTIME,
        "alarm_stats_session" to BackupDecision.SKIP_RUNTIME,
        "alarm_sync_pending" to BackupDecision.SKIP_RUNTIME,
        "decikeeper_lock_window_state" to BackupDecision.SKIP_RUNTIME,
        "decikeeper_startup_state" to BackupDecision.SKIP_RUNTIME,
        "presence_notif_state" to BackupDecision.SKIP_RUNTIME,
        "pending_main_activity_extras" to BackupDecision.SKIP_RUNTIME,
        "rest_stats_grant_marker" to BackupDecision.SKIP_RUNTIME,
        "rest_stats_repair" to BackupDecision.SKIP_RUNTIME,
        "baseline_one_time_repairs" to BackupDecision.SKIP_RUNTIME,
        "version_fingerprint" to BackupDecision.SKIP_RUNTIME,
        "return_home_buffer" to BackupDecision.SKIP_RUNTIME,
        // 已展示过的预警等级（按时段去重），不是设置
        "reminder_alerts" to BackupDecision.SKIP_RUNTIME,
        // 旧版“观察期/晋升”遗留：代码只会清除这些标记，已无功能
        "decikeeper_probation" to BackupDecision.SKIP_RUNTIME,
        "decikeeper_promotion" to BackupDecision.SKIP_RUNTIME,
        "app_window_promotion" to BackupDecision.SKIP_RUNTIME,

        // ── 待评审：用户设置或日志，值得导入但需要先设计，不是“决定不导入” ──
        // 生效策略本身是规则参数，导入必须遵守“加严立即、放宽延期”
        "effective_activation_policy" to BackupDecision.PENDING_REVIEW,
        // 只存条目 JSON，音频文件在应用内部目录且不在备份里，单导入会指向不存在的文件
        "alarm_custom_ringtones_prefs" to BackupDecision.PENDING_REVIEW,
        // 界面主题：单个字符串，无风险，但不属于现有六个备份分类，需要决定放哪
        "decikeeper_ui_theme" to BackupDecision.PENDING_REVIEW,
        "bookmark_settings" to BackupDecision.PENDING_REVIEW,
        "baseline_prefs" to BackupDecision.PENDING_REVIEW,
        // 规则变更日志/记录：描述的是原设备上发生过的变更，带生效时间，导入后会与新设备的真实待生效状态混在一起
        "decikeeper_changelog" to BackupDecision.PENDING_REVIEW,
        "decikeeper_rules_log" to BackupDecision.PENDING_REVIEW,
        "rule_change_ledger" to BackupDecision.PENDING_REVIEW
    )

    fun decisionOf(prefsName: String): BackupDecision? = decisions[prefsName]
}
