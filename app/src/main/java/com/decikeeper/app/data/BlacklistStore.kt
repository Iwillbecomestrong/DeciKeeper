package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.ChangeLogManager
import com.decikeeper.app.manager.ConfigPendingManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.service.MonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale





/**
 * 黑名单持久化簇（自 CreditManager 拆出，行为零变化）。
 * 外部仍通过 CreditManager.getBlacklistApps(...) 等转发调用，对外接口零改动。
 * prefs 文件名与 key 均与原实现逐字一致。
 */
object BlacklistStore {

    // 黑名单相关 prefs key（值与原 CreditManager 逐字一致）
    internal const val KEY_BLACKLIST_APPS = "blacklist_apps_list"
    private const val KEY_PENDING_BLACKLIST_APPS = "blacklist_apps_list" // Changed to match stagePending logic
    private const val PREFS_PENDING_APPS = "decikeeper_pending_apps"
    private const val KEY_USER_REMOVED_APPS = "user_removed_apps" // 🔴 用户显式移除的 App（防止 periodicAutoBlacklistScan 重新加入）

    const val KEY_AUTO_BLACKLIST_ENABLED = "auto_blacklist_new_apps_enabled"
    const val KEY_AUTO_BLACKLIST_CATEGORIES = "auto_blacklist_categories"
    val DEFAULT_AUTO_BLACKLIST_CATEGORIES = setOf("comm", "video", "game", "browser")

    fun isAutoBlacklistEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        // 读取已安装应用列表属于用户主动配置的能力，首次安装不自动扫描。
        return prefs.getBoolean(KEY_AUTO_BLACKLIST_ENABLED, false)
    }

    fun setAutoBlacklistEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_BLACKLIST_ENABLED, enabled).apply()
    }

    fun getAutoBlacklistCategories(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_AUTO_BLACKLIST_CATEGORIES, DEFAULT_AUTO_BLACKLIST_CATEGORIES)?.toSet()
            ?: DEFAULT_AUTO_BLACKLIST_CATEGORIES
    }

    fun setAutoBlacklistCategories(context: Context, categories: Set<String>) {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_AUTO_BLACKLIST_CATEGORIES, HashSet(categories)).apply()
    }

    // 🚀 [Optimization] Memory Cache for Blacklist (Avoid frequent SP reads)
    @Volatile
    private var cachedBlacklist: Set<String>? = null

    internal fun invalidateBlacklistCache() {
        cachedBlacklist = null
    }

    internal fun setCachedBlacklist(value: Set<String>) {
        cachedBlacklist = value
    }

    fun isFirstSetup(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_BLACKLIST_APPS, null) == null
    }

    fun getBlacklistApps(context: Context): Set<String> {
        // 🚀 Fast Return from Memory Cache
        cachedBlacklist?.let { return it }

        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val list = prefs.getStringSet(KEY_BLACKLIST_APPS, null)?.toSet() ?: emptySet()

        cachedBlacklist = list
        return list
    }

    // ═══════════════════════════════════════════════════════════════
    // 用户显式移除跟踪（防止 periodicAutoBlacklistScan 重新加入）
    // ═══════════════════════════════════════════════════════════════

    internal fun addUserRemovedApps(context: Context, pkgs: Set<String>) {
        if (pkgs.isEmpty()) return
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_USER_REMOVED_APPS, null)?.toMutableSet() ?: mutableSetOf()
        current.addAll(pkgs)
        prefs.edit().putStringSet(KEY_USER_REMOVED_APPS, HashSet(current)).apply()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                context.createDeviceProtectedStorageContext().getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putStringSet(KEY_USER_REMOVED_APPS, HashSet(current)).apply()
            } catch (_: Exception) {}
        }
    }

    internal fun clearUserRemovedApps(context: Context, pkgs: Set<String>) {
        if (pkgs.isEmpty()) return
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_USER_REMOVED_APPS, null)?.toMutableSet() ?: return
        current.removeAll(pkgs)
        prefs.edit().putStringSet(KEY_USER_REMOVED_APPS, HashSet(current)).apply()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                context.createDeviceProtectedStorageContext().getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putStringSet(KEY_USER_REMOVED_APPS, HashSet(current)).apply()
            } catch (_: Exception) {}
        }
    }

    fun getUserRemovedApps(context: Context): Set<String> {
        return context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_USER_REMOVED_APPS, null)?.toSet() ?: emptySet()
    }

    /** 黑名单变更播报 */
    private fun logBlacklistChanges(context: Context, before: Set<String>, after: Set<String>, effective: String) {
        val added = after.minus(before)
        val removed = before.minus(after)
        if (added.isEmpty() && removed.isEmpty()) return
        val addedNames = added.map { CreditManager.getAppLabel(context, it) }
        val removedNames = removed.map { CreditManager.getAppLabel(context, it) }
        LogExporter.i("CreditManager", "BLACKLIST_CHANGED | added=${addedNames.joinToString()} | removed=${removedNames.joinToString()} | effective=$effective")
    }

    // 🚀 [Refactor] Categorized App Lists for Quick Import
    // 覆盖各大应用市场对应类别下载量前列的 App
    fun getBlacklistCategory(type: String): Set<String> {
        return when(type) {
            "comm" -> setOf(
                // 国内社交
                "com.tencent.mobileqq",     // QQ
                "com.tencent.tim",          // TIM (QQ轻聊版)
                "com.tencent.mm",           // WeChat
                "com.sina.weibo",           // Weibo
                "com.zhihu.android",        // Zhihu
                "com.douban.frodo",         // Douban
                "com.baidu.tieba",          // Baidu Tieba
                "com.joox",                 // 最右
                "cn.xiaochuankeji.tieba",   // 最右 (旧包名)
                "com.immomo.momo",          // 陌陌
                "com.p1.mobile.putong",     // 探探
                "cn.soulapp.android",       // Soul
                "com.tencent.qzone",        // QQ空间

                // 国际社交
                "com.whatsapp",             // WhatsApp
                "org.telegram.messenger",   // Telegram
                "com.instagram.android",    // Instagram
                "com.facebook.katana",      // Facebook
                "com.twitter.android",      // X (Twitter)
                "com.reddit.frontpage",     // Reddit
                "com.discord",              // Discord
                "com.snapchat.android",     // Snapchat
                "jp.naver.line.android",    // LINE
                "com.linkedin.android",     // LinkedIn
                "com.pinterest",            // Pinterest
                "com.tumblr"                // Tumblr
            )
            "video" -> setOf(
                // 国内短视频/直播
                "com.xingin.xhs",           // Xiaohongshu
                "com.ss.android.ugc.aweme", // Douyin
                "com.ss.android.ugc.aweme.lite", // Douyin Lite (极速版)
                "com.ss.android.yumme.video", // Douyin Jingxuan
                "com.ss.android.ugc.live",   // Douyin HuoShan
                "com.smile.gifmaker",       // Kuaishou
                "com.kuaishou.nebula",      // Kuaishou Lite (极速版)
                "com.kuaishou.hquser",       // Kuaishou Concept
                "tv.danmaku.bili",          // Bilibili
                "tv.danmaku.bilibilihd",    // Bilibili HD
                "com.ss.android.ugc.aweme.hd", // Douyin HD
                "com.ss.android.article.video", // 西瓜视频
                "com.tencent.qqlive",       // 腾讯视频
                "com.qiyi.video",           // 爱奇艺
                "com.youku.phone",          // 优酷
                "com.hunantv.imgo.activity", // 芒果TV
                "com.cctv.yangshipin.app.androidp", // 央视频
                "com.tencent.weishi",       // 微视
                "com.meitu.meipaimv",       // 美拍
                "com.yixia.videoeditor",    // 秒拍
                "com.luna.music",           // 汽水音乐
                "com.tencent.karaoke",      // 全民K歌

                // 国际短视频/视频
                "com.zhiliaoapp.musically", // TikTok
                "com.ss.android.ugc.trill", // TikTok (legacy)
                "com.google.android.youtube", // YouTube
                "com.google.android.apps.youtube.music", // YouTube Music
                "com.netflix.mediaclient",  // Netflix
                "com.amazon.avod.thirdpartyclient", // Amazon Prime Video
                "com.disney.disneyplus",    // Disney+
                "com.hbo.hbonow"            // HBO Max
            )
            "game" -> setOf(
                // 国内手游
                "com.tencent.tmgp.sgame",   // 王者荣耀
                "com.tencent.tmgp.pubgmhd", // 和平精英 (Game for Peace)
                "com.tencent.tmgp.pubgmhd.bz", // 和平精英体验服
                "com.miHoYo.Yuanshen",      // 原神
                "com.miHoYo.hkrpg",         // 崩坏:星穹铁道
                "com.miHoYo.enterprise.NGHOD", // 绝区零
                "com.tencent.lolm",         // 英雄联盟手游
                "com.tencent.tmgp.kgame",   // 金铲铲之战
                "com.netease.dwrg",         // 第五人格
                "com.netease.harrypotter",  // 哈利波特:魔法觉醒
                "com.netease.onmyoji",      // 阴阳师
                "com.hypergryph.arknights", // 明日方舟
                "com.tencent.tmgp.supercell.clashroyale", // 皇室战争
                "com.supercell.clashofclans", // 部落冲突
                "com.tencent.tmgp.supercell.clashofclans", // 部落冲突 (腾讯版)
                "com.netease.egame",        // 蛋仔派对
                "com.tencent.tmgp.cf",      // 穿越火线手游
                "com.tencent.tmgp.cod",     // Call of Duty 手游 (国服)
                "com.netease.newsreader",   // 我的世界 (网易)
                "com.tencent.tmgp.WePop",   // 和平精英国际版
                "com.netease.party",        // 永劫无间手游
                "com.kurogame.mingchao",    // 鸣潮

                // 国际手游
                "com.tencent.ig",           // PUBG MOBILE
                "com.mobile.legends",       // Mobile Legends: Bang Bang
                "com.activision.callofduty.shooter", // Call of Duty: Mobile
                "com.dts.freefiremax",      // Free Fire MAX
                "com.dts.freefireth",       // Free Fire
                "com.epicgames.fortnite",   // Fortnite
                "com.nianticlabs.pokemongo", // Pokémon GO
                "com.riotgames.league.wildrift", // League of Legends: Wild Rift
                "com.netease.lztgglobal",   // 黎明杀机手游
                "com.mojang.minecraftpe"    // Minecraft
            )
            "browser" -> setOf(
                // Chrome family
                "com.android.chrome",                   // Chrome
                "com.chrome.beta",                      // Chrome Beta
                "com.chrome.dev",                       // Chrome Dev
                "com.chrome.canary",                    // Chrome Canary
                "com.google.android.googlequicksearchbox", // Google App

                // 国际浏览器
                "org.mozilla.firefox",                  // Firefox
                "org.mozilla.firefox_beta",             // Firefox Beta
                "org.mozilla.focus",                    // Firefox Focus
                "com.microsoft.emmx",                   // Edge
                "com.brave.browser",                    // Brave
                "com.opera.browser",                    // Opera
                "com.opera.gx",                         // Opera GX
                "com.opera.mini.native",               // Opera Mini
                "com.duckduckgo.mobile.android",       // DuckDuckGo

                // 国内浏览器
                "com.tencent.mtt",                      // QQ Browser
                "com.UCMobile",                         // UC Browser
                "com.UCMobile.intl",                    // UC Browser (国际版)
                "com.qihoo.browser",                    // 360 Browser
                "com.baidu.searchbox",                  // Baidu App
                "com.baidu.browser.apps",               // 百度浏览器
                "com.quark.browser",                    // 夸克
                "com.sogou.browser",                    // 搜狗浏览器
                "mark.via",                             // Via Browser
                "com.mmbox.browser",                    // X Browser

                // 厂商浏览器
                "com.huawei.browser",                   // Huawei Browser
                "com.mi.globalbrowser",                 // Mi Browser (Global)
                "com.android.browser",                  // Generic / Mi Browser (CN)
                "com.vivo.browser",                     // Vivo Browser
                "com.heytap.browser",                   // OPPO/OnePlus (HeyTap)
                "com.coloros.browser",                  // OPPO (ColorOS)
                "com.sec.android.app.sbrowser"          // Samsung Internet
            )
            else -> emptySet()
        }
    }

    fun saveBlacklistApps(context: Context, newApps: Set<String>): Long {
        val result = analyzeAndSaveBlacklistApps(context, newApps)
        CreditManager.emitBlacklistConfigChange(context, newApps, result)
        return when (result) {
            is AnalysisResult.Mixed -> result.effectiveTime
            else -> 0L
        }
    }

    fun analyzeAndSaveBlacklistApps(context: Context, newApps: Set<String>): AnalysisResult {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        
        // 🚀 Adaptation Period: Always Immediate
        if (CreditManager.isInAdaptationPeriod(context)) {
            val currentBefore = getBlacklistApps(context)
            val activeRemovals = currentBefore.minus(newApps)
            val activeAdditions = newApps.minus(currentBefore)

            if (activeRemovals.isNotEmpty()) {
                addUserRemovedApps(context, activeRemovals)
            }
            if (activeAdditions.isNotEmpty()) {
                clearUserRemovedApps(context, activeAdditions)
            }

            prefs.edit().putStringSet(KEY_BLACKLIST_APPS, HashSet(newApps)).apply()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    context.createDeviceProtectedStorageContext().getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putStringSet(KEY_BLACKLIST_APPS, HashSet(newApps)).apply()
                } catch (_: Exception) {}
            }
            cachedBlacklist = newApps
            context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE).edit().clear().apply()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
                        .edit().clear().apply()
                } catch (_: Exception) {}
            }

            // 🔴 记录用户显式移除/重新加入的 App（防止 periodicAutoBlacklistScan 重新自动加入！）
            val removed = currentBefore.minus(newApps)
            val added = newApps.minus(currentBefore)
            if (removed.isNotEmpty()) {
                addUserRemovedApps(context, removed)
            }
            if (added.isNotEmpty()) {
                clearUserRemovedApps(context, added)
            }

            // Sync Service
            val intent = android.content.Intent(context, MonitorService::class.java)
            intent.action = MonitorService.ACTION_REFRESH_RULES
            context.startService(intent)

            // 变更播报
            logBlacklistChanges(context, currentBefore, newApps, "立即生效(适应期)")
            return AnalysisResult.AllImmediate()
        }

        val currentApps = getBlacklistApps(context)
        val baselineApps = getBaselineBlacklistApps(context)
        
        // 🚀 Additions vs CURRENT active: What we need to add to the system now.
        val activeAdditions = newApps.minus(currentApps)
        
        // 🚀 Analysis vs BASELINE: What is "loosening" compared to the INITIAL SNAPSHOT.
        // User Logic: "Adverse" means looser than Baseline. "Favorable" means stricter than Baseline.
        // If New is stricter than Baseline -> All Immediate
        // If New is loosely than Baseline -> Delayed
        
        val baselineAdditions = newApps.minus(baselineApps) // New apps not in baseline (Stricter)
        val baselineRemovals = baselineApps.minus(newApps) // Apps in baseline REMOVED (Looser)
        val activeRemovals = currentApps.minus(newApps)

        // 🔴 记录用户显式移除/重新加入的 App（防止 periodicAutoBlacklistScan 重新加入）
        val allUserRemoved = baselineRemovals + activeRemovals
        if (allUserRemoved.isNotEmpty()) {
            addUserRemovedApps(context, allUserRemoved)
        }
        if (baselineAdditions.isNotEmpty() || activeAdditions.isNotEmpty()) {
            clearUserRemovedApps(context, baselineAdditions + activeAdditions)
        }

        if (activeAdditions.isEmpty() && baselineRemovals.isEmpty() && currentApps == newApps) {
        // 🚀 [Fix] If reverting to active state, clear any pending changes (Cancel Pending)
        if (hasPendingApps(context)) {
            context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE).edit().clear().apply()
            // 🚀 [Fix] Also clear pending change logs
            ChangeLogManager.clearPendingLogsForCategory(context, "blacklist")
        }
            return AnalysisResult.NoChange
        }
        
        // 🚀 Correction Logic: Active = Baseline + Current Proposal
        // Any app in proposal NOT in baseline is an immediate addition.
        // Any app in baseline NOT in proposal is a delayed removal (so it must stay in active).
        val newActive = baselineApps + newApps
        prefs.edit().putStringSet(KEY_BLACKLIST_APPS, HashSet(newActive)).apply()
        cachedBlacklist = newActive
        
        // Sync Service Immediate for Additions
        // Sync Service Immediate for Additions
        // 🚀 [Fix] Use 'baselineAdditions' which represents strict new additions
        if (activeAdditions.isNotEmpty() || baselineAdditions.isNotEmpty()) {
            val intent = android.content.Intent(context, MonitorService::class.java)
            intent.action = MonitorService.ACTION_REFRESH_RULES
            context.startService(intent)
        }

        // 🚀 [ChangeLog] Cleanup History (Partial Revert Support)
        val restoredApps = newApps.intersect(baselineApps)
        restoredApps.forEach { pkg ->
            ChangeLogManager.removePendingEntriesByTag(context, "blacklist", "blacklist_remove_$pkg")
        }

        if (baselineRemovals.isEmpty()) {
            // Pure Beneficial (or restoration to baseline) -> Immediate
            // If we have strictly MORE apps than baseline (or equal), it is Favorable.
            context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE).edit().clear().apply()
            
            // 🚀 [ChangeLog] Log favorable changes (Additions)
            // Note: If we are just restoring, we might NOT want to log "Added App (Favorable)" if we just cleared the "Removed App" log.
            // But if it's a NEW addition (not in baseline), we should log it.
            // Actually, `sessionAdditions` = New - Baseline. So if we are restoring, `sessionAdditions` is empty!
            // Wait, if we Restore:
            // Baseline: {A, B}
            // Active: {A, B}
            // User Removes B -> Active {A}, Pending {Remove B}
            // User Adds B -> New {A, B}
            // Baseline {A, B}
            // sessionAdditions = {A,B} - {A,B} = Empty.
            // sessionRemovals = {A,B} - {A,B} = Empty.
            // So we enter this block.
            // `restoredApps` = {A, B}.
            // We remove "blacklist_remove_B" from entries. Correct.
            // `immList` comes from `sessionAdditions`. It is empty. So no "Added B" log.
            // This is PERFECT. It silently cancels out the pending removal.
            
            val pm = context.packageManager
            // 🚀 Only log if it's a NEW restriction relative to Baseline (True Favorable)
            val trueNewRestrictions = newApps.minus(baselineApps)
            
            val logItems = trueNewRestrictions.map { pkg ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch(e:Exception){ pkg }
                val msg = "新增屏蔽: $label (有利)"
                Triple(msg, false, "blacklist_status_$pkg")
            }
            
            val msgs = logItems.map { it.first }
            if (logItems.isNotEmpty()) {
                 ChangeLogManager.addEntries(context, "blacklist", logItems)
            }
            // Note: Reverted logic removed as it was confusing. We use silent cancellation now.

            // 变更播报
            logBlacklistChanges(context, currentApps, newApps, "立即生效")
            return AnalysisResult.AllImmediate(msgs)
        } else {
            // 🚀 [Diff Base] Comparison for User Feedback (Get Prev Pending BEFORE overwriting)
            val pendingPrefs = context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
            val pendingSetString = pendingPrefs.getStringSet(KEY_PENDING_BLACKLIST_APPS, null)
            val baseApps = pendingSetString ?: currentApps // Reference for "What changed JUST NOW"
            
            // Mixed/Adverse -> Additions are active (above), Removals are Delayed (Pending)
            val effectiveTime = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())
            stagePendingBlacklistApps(context, newApps, effectiveTime)
            
            // 🚀 [Incremental Diff]
            // We compare New vs Base (Last Pending/Active) to see what the user JUST did.
            // THEN we check the nature of those specific changes against Baseline.
            val incrementalAdded = newApps.minus(baseApps)
            val incrementalRemoved = baseApps.minus(newApps)
            
            val pm = context.packageManager
            val immMsgs = mutableListOf<String>()
            val resMsgs = mutableListOf<String>()
            val delMsgs = mutableListOf<String>()
            val logItems = mutableListOf<Triple<String, Boolean, String?>>()
            
            fun getLabel(pkg: String) = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch(e:Exception){ pkg }

            // 1. Handle Added Apps (User clicked block)
            incrementalAdded.forEach { pkg ->
                val label = getLabel(pkg)
                if (baselineApps.contains(pkg)) {
                    // It was in baseline, so adding it back = Restoring restrictions = Favorable (Immediate)
                    val msg = "新增屏蔽: $label [恢复]"
                    resMsgs.add(msg)
                    // Tag ensures this overwrites any pending removal for same package
                    logItems.add(Triple(msg, false, "blacklist_status_$pkg"))
                } else {
                    // It was NOT in baseline, new restriction = Favorable (Immediate)
                    val msg = "新增屏蔽: $label (有利)"
                    immMsgs.add(msg)
                    logItems.add(Triple(msg, false, "blacklist_status_$pkg"))
                }
            }
            
            // 2. Handle Removed Apps (User clicked unblock)
            incrementalRemoved.forEach { pkg ->
                val label = getLabel(pkg)
                if (!baselineApps.contains(pkg)) {
                     // It was NOT in baseline (so it was a new addition). Removing it = Reverting to baseline = Restoring.
                     // Wait, if I added it (above baseline) and now remove it. I am relaxing relative to Previous.
                     // But strictly, New state == Baseline state (for this app).
                     // So specific app state is "Restored to Baseline". 
                     // Therefore Immediate.
                    val msg = "取消解析/撤销新增: $label [恢复]"
                    resMsgs.add(msg)
                    // Removing the "Added" log if exists? 
                    // Actually we log "Status: Restored".
                    logItems.add(Triple(msg, false, "blacklist_status_$pkg"))
                } else {
                    // It WAS in baseline. Removing it -> Looser than Baseline -> Adverse.
                    val msg = "解除屏蔽: $label (不利)"
                    delMsgs.add(msg)
                    logItems.add(Triple(msg, true, "blacklist_status_$pkg"))
                }
            }
            
            // 🚀 [ChangeLogSync] Batch add with deduplication
            if (logItems.isNotEmpty()) {
                ChangeLogManager.addEntries(context, "blacklist", logItems)
            }
            
            if (immMsgs.isEmpty() && delMsgs.isEmpty() && resMsgs.isEmpty()) {
                return AnalysisResult.NoChange
            }

            // 变更播报
            val effectiveTimeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(effectiveTime))
            logBlacklistChanges(context, currentApps, newApps, "延迟生效($effectiveTimeStr)")
            return AnalysisResult.Mixed(immMsgs, delMsgs, resMsgs, emptyList(), effectiveTime, 0L)
        }
    }

    // ==========================================
    // 🔵 Baseline Blacklist
    // ==========================================
    fun saveBaselineBlacklist(context: Context) {
        val currentApps = getBlacklistApps(context)
        context.getSharedPreferences("baseline_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("baseline_blacklist_apps", HashSet(currentApps))
            .apply()
    }
    
    fun getBaselineBlacklistApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("baseline_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("baseline_blacklist_apps", null)
        
        if (set == null) {
            // 🚀 [Fix] Fallback to Current Active Apps if Baseline is missing
            // This prevents the "All New" bug on first run or if baseline wasn't saved.
            val current = getBlacklistApps(context)
            // Auto-initialize baseline to current for stability
            prefs.edit().putStringSet("baseline_blacklist_apps", HashSet(current)).apply()
            return current
        }
        return set.toSet()
    }

    fun saveTargetAppsImmediately(context: Context, apps: Set<String>) {
         // Force immediate save (e.g. restore backup)
         context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_BLACKLIST_APPS, HashSet(apps)).apply()
         cachedBlacklist = apps // Update Cache
    }

    fun stagePendingBlacklistApps(context: Context, apps: Set<String>, effectiveTime: Long) {
        val prefs = context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_BLACKLIST_APPS, HashSet(apps))
            .putLong("effective_timestamp", effectiveTime) // 🟢 Save Timestamp
            .putBoolean("has_pending_apps", true)
            .apply()
    }

    fun getPendingBlacklistApps(context: Context): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("has_pending_apps", false)) return null
        return prefs.getStringSet(KEY_BLACKLIST_APPS, null)?.toSet()
    }

    fun getPendingTargetApps(context: Context): Set<String>? = getPendingBlacklistApps(context)

    fun stagePendingTargetApps(context: Context, apps: Set<String>, isWhitelist: Boolean) = stagePendingBlacklistApps(context, apps, 0L)

    fun hasPendingApps(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
            .getBoolean("has_pending_apps", false)
    }

    fun revokePendingChanges(context: Context) {
        context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE).edit().clear().apply()
        ChangeLogManager.clearPendingLogsForCategory(context, "blacklist")
        LogExporter.i("CreditManager", "Revoked all pending Blacklist changes.")
    }

    fun applyPendingAppsToActive(context: Context) {
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING_APPS, Context.MODE_PRIVATE)
        if (!pendingPrefs.getBoolean("has_pending_apps", false)) return

        // 🟢 Check Effective Time
        val effectiveTime = pendingPrefs.getLong("effective_timestamp", 0L)
        val now = System.currentTimeMillis()
        if (now < effectiveTime) return

        val pendingApps = pendingPrefs.getStringSet(KEY_BLACKLIST_APPS, null)?.toSet() ?: emptySet()
        val currentApps = getBlacklistApps(context)
        val added = pendingApps.minus(currentApps)
        val removed = currentApps.minus(pendingApps)

        // Force save immediately to active
        saveTargetAppsImmediately(context, pendingApps)
        pendingPrefs.edit().clear().apply()

        // 🚀 [Fix] Mark ChangeLogs as Applied
        ChangeLogManager.markCategoryAsApplied(context, "blacklist")

        // 延迟生效播报
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            val addedNames = added.map { CreditManager.getAppLabel(context, it) }
            val removedNames = removed.map { CreditManager.getAppLabel(context, it) }
            LogExporter.i("CreditManager", "BLACKLIST_DELAYED_APPLIED | added=${addedNames.joinToString()} | removed=${removedNames.joinToString()}")
        }
    }

}
