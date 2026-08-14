/**
 * =================================================================================
 * [Context Header]
 * * @file: AntiUninstallProtection.kt
 * * @role: Domain Skill
 * * @description: 防卸载拦截保护逻辑。
 * * @responsibilities:
 * 1. 识别卸载相关界面。
 * 2. 触发拦截与告警处理。
 * * @context:
 * - 上游: ProtectionManager
 * - 下游: LogExporter, LockWindowManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 需保证高响应。
 * 3. 空值安全。
 * =================================================================================
 */
package com.ironwill.app.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ironwill.app.AntiUninstallManager
import com.ironwill.app.AccessibilityControlManager
import com.ironwill.app.LogExporter
import com.ironwill.app.PrivacyProtectionManager
import com.ironwill.app.R

class AntiUninstallProtection(private val context: Context) {

    companion object {
        const val TAG = "AntiUninstall"

        private const val EVENT_LOG_THROTTLE_MS = 1000L
        private const val CHECK_LOG_THROTTLE_MS = 800L
        private var lastEventLogTime = 0L
        private var lastEventLogPackage: String? = null
        private var lastCheckLogTime = 0L
        
        // Global dialog state shared across protection modules.
        @Volatile var isDialogShowing = false
        @Volatile var hasPendingDialog = false
        
        // Allowed "Safe" apps (system components only).
        val SAFE_PACKAGES_KEYWORDS = listOf(
            "intentresolver", "resolver"  // Android System Share Sheet (鍒嗕韩閫夋嫨鍣?
        )
        
        // Vivo Launcher Packages
        val VIVO_LAUNCHER_PACKAGES = setOf(
            "com.bbk.launcher2", "com.vivo.launcher", "com.vivo.upslide"
        )

        // Monitored Packages (Installers, Stores, Settings)
        // CRITICAL: Only scan these for "Uninstall" keywords to avoid Launcher false positives
        val MONITORED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter",           // Xiaomi
            "com.huawei.systemmanager",          // Huawei
            "com.coloros.safecenter",            // OPPO
            "com.vivo.permissionmanager",        // Vivo
            "com.samsung.android.sm",            // Samsung
            "com.oneplus.security",              // OnePlus
            "com.oplus.safecenter",              // OPPO/Realme
            "com.vivo.imanager",                 // Vivo iManager
            "com.iqoo.secure",                   // Vivo/iQOO Secure
            
            // App Stores
            "com.bbk.appstore",         // Vivo App Store
            "com.oppo.market",          // Oppo App Store
            "com.xiaomi.market",        // Xiaomi App Store
            "com.huawei.appmarket",     // Huawei App Gallery
            "com.android.vending",      // Google Play Store
            "com.coolapk.market",       // Coolapk
            "com.android.vending",      // Google Play Store
            "com.coolapk.market",       // Coolapk
            "com.tencent.android.qqdownloader", // Tencent MyApp
            // Fallback for custom launchers not caught by specific logic
            "com.bbk.launcher2", "com.vivo.launcher", "com.vivo.upslide",
            "com.huawei.android.launcher", "com.miui.home", "com.oppo.launcher"
        )

        val PROTECTED_APP_NAMES = setOf("IronWill", "Iron Will", "意引", "钢铁意志")
        
        // Multi-Language Uninstall Keywords
        val UNINSTALL_KEYWORDS = setOf(
            // 1. Chinese & English
            "卸载", "删除应用", "移除", "强行停止", "停用", // SC
            "解除安裝", "刪除應用程式", "移除", "強制停止", "停用", // TC
            "Uninstall", "Force stop", "Disable",

            // 2. East Asia
            "アンインストール", "削除", "強制停止", "無効にする", // Japanese
            "설치 삭제", "제거", // Korean

            // 3. Southeast Asia
            "Gỡ cài đặt", // Vietnamese
            "ถอนการติดตั้ง", // Thai
            "Hapus instalan", "Copot pemasangan", "Uninstal", // Indonesian
            "Nyahpasang", // Malay
            "I-uninstall", // Filipino
            "ຖອນການຕິດຕັ້ງ", // Lao
            "ဖြုတ်မည်", // Burmese
            "លុបការដំឡើង", // Khmer

            // 4. South Asia
            "अनइंस्टॉल", // Hindi
            "अनइन्स्टल", // Nepali
            "আনইনস্টল", // Bangla
            "අස්ථාපනය", // Sinhala
            "ان انسٹال", // Urdu

            // 5. Europe & Americas
            "Deinstallieren", // German
            "Desinstalar", // Spanish & Portuguese
            "Désinstaller", // French
            "Disinstalla", // Italian
            "Verwijderen", "Deïnstalleren", // Dutch
            "Удалить", "Деинсталлировать", // Russian

            // 6. West Asia / Central Asia
            "إلغاء التثبيت", // Arabic
            "ئۆچۈرۈش", // Uyghur
            "ཕྱིར་འདོན་" // Tibetan
        )


        // Safe Context Keywords (History, etc.)
        val SEARCH_HISTORY_KEYWORDS = listOf(
            "Search history", "最近搜索", "清除历史", "历史记录",
            "搜尋紀錄", "最近搜尋", "搜索紀錄", "検索履歴", "履歴", "검색 기록", "기록",
            "Suchverlauf", "Historial de búsqueda", "Historique de recherche", "Cronologia ricerche",
            "Histórico de pesquisa", "Zoekgeschiedenis", "История поиска",
            "Lịch sử tìm kiếm", "ประวัติการค้นหา", "Histori penelusuran", "Sejarah carian",
            "Kasaysayan ng paghahanap", "سجل البحث", "खोज इतिहास", "سجل", "تلاش کی سرگزشت"
        )
        
        // 馃敀 [Safe Context] 鏃犻殰纰嶈缃鎯呴〉鍏抽敭璇?- 鐢ㄦ埛鍦ㄦ椤甸潰绠＄悊IronWill鐨勬棤闅滅鏉冮檺锛屼笉搴旇Е鍙戦槻鍗歌浇
        val ACCESSIBILITY_CONTEXT_KEYWORDS = listOf(
            // 中文
            "无障碍", "辅助功能", "已下载的服务", "已下载的应用", "使用 IronWill", "使用IronWill",
            "無障礙", "輔助功能", "已下載的服務", "已下載的應用程式",
            // 英语
            "Accessibility", "Downloaded services", "Downloaded apps", "Use IronWill",
            // 日语
            "ユーザー補助", "ダウンロードしたサービス", "ダウンロード済みのアプリ", "IronWillを使用",
            // 韩语
            "접근성", "다운로드한 서비스", "다운로드한 앱",
            // 德语
            "Bedienungshilfen", "Heruntergeladene Dienste",
            // 法语
            "Accessibilité", "Services téléchargés",
            // 西班牙语
            "Accesibilidad", "Servicios descargados",
            // 俄语
            "Специальные возможности", "Скачанные сервисы"
        )

        // 馃敀 [Safe Context] 闀挎寜鑿滃崟瀹夊叏鍏抽敭璇?- 杩欎簺璇嶅嚭鐜版椂閫氬父琛ㄧず鏄暱鎸夎彍鍗曪紙鍖呭惈"鍗歌浇"閫夐」浣嗕笉涓€瀹氭槸绔嬪嵆鍗歌浇锛?        // 鎴戜滑甯屾湜鍏佽鐢ㄦ埛鐐瑰嚮"鍒嗕韩"鎴?搴旂敤淇℃伅"锛屼絾鍙湁鍦ㄥ嚭鐜扮湡姝ｅ嵏杞界‘璁ゆ鏃舵墠鎷︽埅
        val SAFE_MENU_KEYWORDS = listOf(
            "Share", "分享", "Recommend", "推荐",
            "App Info", "应用信息", "App details", "应用详情",
            "Edit", "编辑", "Sort", "排序", "Lock", "锁定",
            "Partager", "Compartir", "Teilen", "共有", "공유"
        )

        val APP_INFO_INDICATORS = listOf(
            "应用信息", "应用详情", "App info", "App details",
            "卸载", "Uninstall", "强行停止", "Force stop",
            "存储", "Storage", "权限", "Permissions",
            "流量", "电量", "通知", "移动数据", "电池"
        )

        val PRIVATE_SPACE_PACKAGES = listOf(
            "com.vivo.abe",
            "com.vivo.xspace",
            "com.vivo.privacylauncher",
            "com.vivo.privacy",
            "com.vivo.privacyspace",
            "com.coloros.privacysystem",
            "com.miui.securitycenter"
        )

        val PRIVATE_SPACE_CONTEXT_KEYWORDS = listOf(
            "原子隐私系统", "隐私系统", "隐私空间", "私密空间", "私人空间",
            "添加应用", "添加到隐私", "移入", "移至", "加入", "添加到", "迁移到",
            "Private Space", "Privacy Space", "Add app", "Add apps", "Move to", "Move app"
        )

        val PRIVATE_SPACE_SEARCH_KEYWORDS = listOf(
            "搜索", "搜尋", "查找", "查詢", "Search", "Find"
        )

        val PRIVATE_SPACE_PACKAGE_HINTS = listOf(
            "privacy", "private", "privacysystem", "safebox", "secret", "secure"
        )

        val PRIVATE_SPACE_LIST_CLASS_HINTS = listOf(
            "list", "recycler", "grid", "search", "edittext"
        )

        val PRIVATE_SPACE_ADD_APP_FOLLOW_UP_CLASS_HINTS = listOf(
            "recycler", "list", "grid", "edittext", "textview", "framelayout", "linearlayout"
        )

        val PRIVATE_SPACE_ADD_APP_ACTIVITY_HINTS = listOf(
            "AppMoveInActivity"
        )

        val PRIVATE_SPACE_ADD_APP_TEXT_KEYWORDS = listOf(
            "添加应用", "添加到隐私", "添加到原子隐私", "移入隐私", "移入原子隐私系统",
            "加入隐私", "移至隐私", "Add app", "Add apps", "Move app", "Move to private"
        )

        val PRIVATE_SPACE_ADD_APP_NODE_HINTS = listOf(
            "appmovein", "move_in", "movein", "add_app", "addapp",
            "apphidden", "search_src_text", "search_plate"
        )

        internal fun isPrivateSpaceAddAppPageCandidate(
            inPrivateSpaceSession: Boolean,
            currentPackage: String,
            rootPackage: String,
            eventClass: String,
            hintContent: String,
            hasRecentConfirmedAddAppPage: Boolean = false
        ): Boolean {
            if (!inPrivateSpaceSession) return false

            val combined = listOf(currentPackage, rootPackage, eventClass, hintContent)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val isMoveInActivity = PRIVATE_SPACE_ADD_APP_ACTIVITY_HINTS.any {
                eventClass.contains(it, ignoreCase = true) || hintContent.contains(it, ignoreCase = true)
            }
            if (isMoveInActivity) return true

            val hasAddAppText = PRIVATE_SPACE_ADD_APP_TEXT_KEYWORDS.any {
                combined.contains(it, ignoreCase = true)
            }
            val hasNodeHint = PRIVATE_SPACE_ADD_APP_NODE_HINTS.any {
                hintContent.contains(it, ignoreCase = true)
            }
            val hasSearchOrListUi = PRIVATE_SPACE_LIST_CLASS_HINTS.any {
                eventClass.contains(it, ignoreCase = true) || hintContent.contains(it, ignoreCase = true)
            } || PRIVATE_SPACE_SEARCH_KEYWORDS.any {
                combined.contains(it, ignoreCase = true)
            }
            val hasFollowUpUi = PRIVATE_SPACE_ADD_APP_FOLLOW_UP_CLASS_HINTS.any {
                eventClass.contains(it, ignoreCase = true) || hintContent.contains(it, ignoreCase = true)
            }

            if (hasAddAppText || (hasNodeHint && hasSearchOrListUi)) return true
            return hasRecentConfirmedAddAppPage && hasFollowUpUi
        }

        internal fun containsProtectedAppToken(content: String, protectedPackageName: String): Boolean {
            if (content.isBlank()) return false
            if (PROTECTED_APP_NAMES.any { content.contains(it, ignoreCase = true) }) return true
            if (content.contains("com.ironwill.app", ignoreCase = true)) return true
            if (content.contains(protectedPackageName, ignoreCase = true)) return true
            val normalized = content.lowercase().replace(Regex("[^a-z0-9\u4e00-\u9fa5]"), "")
            if (normalized.contains("ironwill")) return true
            if (normalized.contains("ironwillapp")) return true
            if (normalized.contains("意引")) return true
            return false
        }

        internal fun shouldBlockPrivateSpaceVisibleTarget(
            protectedPackageName: String,
            vararg contentCandidates: String
        ): Boolean {
            return contentCandidates.any { containsProtectedAppToken(it, protectedPackageName) }
        }
    }

    private var mLastBlockTime: Long = 0
    private var lastLauncherHandleAt: Long = 0L
    private var lastLauncherHandleSignature: String? = null
    private var lastPrivateSpaceBlockAt: Long = 0L
    private var lastPrivateSpaceBlockSignature: String? = null
    private var privateSpaceSessionUntil: Long = 0L
    private var privateSpaceLastSourcePkg: String? = null
    private var privateSpaceAddAppPageUntil: Long = 0L
    private var privateSpaceAddAppPagePkg: String? = null
    private var privateSpaceAddAppPageRootPkg: String? = null
    private var lastPrivateSpaceMatchedSample: String? = null
    private var lastPrivateSpaceGateMissLogAt: Long = 0L
    private var lastPrivateSpaceMissLogAt: Long = 0L
    
    // [App Info Page Tracking] Track app info page for delayed CONTENT_CHANGED scans.
    private var mInAppInfoPagePackage: String? = null
    private var mInAppInfoPageClass: String? = null
    private var mInAppInfoPageTime: Long = 0
    private val APP_INFO_PAGE_TIMEOUT = 5000L  // 5 seconds window for delayed scans.
    private var mVivoMenuArmTime: Long = 0L
    private val VIVO_MENU_ARM_TTL_MS = 45000L
    private var mUninstallHotWindowUntil: Long = 0L
    private val UNINSTALL_HOT_WINDOW_MS = 5000L
    private val PRIVATE_SPACE_SESSION_TTL_MS = 15000L
    private val PRIVATE_SPACE_ADD_APP_PAGE_TTL_MS = 6000L

    fun check(service: AccessibilityService, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        scanStartTime = System.currentTimeMillis()
        val currentPackage = event.packageName?.toString() ?: return
        val eventClassName = event.className?.toString() ?: ""
        
        // Debug log with throttle to avoid UI jank during high-frequency events.
        val now = System.currentTimeMillis()
        if (now - lastCheckLogTime >= CHECK_LOG_THROTTLE_MS) {
            lastCheckLogTime = now
            Log.v(TAG, "[Check] Pkg=$currentPackage EventType=${event.eventType} Class=$eventClassName")
        }
        
        // [Self-Protection] 仅在“当前事件是我们自己的弹窗”时跳过，
        // 不能因为 pending 队列就暂停全量检测，否则会出现防卸载盲区。
        val isOurDialogEvent =
            (currentPackage == context.packageName || currentPackage == "android") &&
                eventClassName.contains("AlertDialog", ignoreCase = true)
        if (isDialogShowing && isOurDialogEvent) {
            Log.d(TAG, "[Skip] Self dialog event while showing=true")
            LogExporter.logEvent(TAG, "[Skip] Self dialog event")
            return
        }
        
        // 馃毇 [Self-Protection] Ignore our own AlertDialog to prevent self-harm!
        // Our anti-uninstall warning dialog must NOT be treated as a threat
        if (eventClassName.contains("AlertDialog", ignoreCase = true) && 
            (currentPackage == context.packageName || currentPackage == "android")) {
            Log.d(TAG, "[Skip] Our own AlertDialog, preventing self-harm!")
            return
        }

        // 0. Force Stop Dialog Detection — 独立于 AntiUninstallManager 状态
        // 强制停止会直接破坏无障碍服务，因此只要 CoreService 在运行（即能收到事件），
        // 就必须拦截。用户明确要求：强制停止拦截的开关应该和无障碍权限一起开关。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fsEventClassName = event.className?.toString() ?: ""
            val fsEventText = event.text?.toString() ?: ""

            val isDialogClass = fsEventClassName.contains("Dialog", ignoreCase = true) ||
                                fsEventClassName.contains("Alert", ignoreCase = true) ||
                                fsEventClassName.contains("VDialog", ignoreCase = true)
            val hasForceStopText = fsEventText.contains("强行停止", ignoreCase = true) || fsEventText.contains("Force stop", ignoreCase = true) ||
                                   fsEventText.contains("要强行停止吗") || fsEventText.contains("强制停止")
            val hasConfirmCancel = fsEventText.contains("确定") || fsEventText.contains("取消") ||
                                   fsEventText.contains("Confirm", ignoreCase = true) || fsEventText.contains("Cancel", ignoreCase = true)

            if (isDialogClass && hasForceStopText && hasConfirmCancel) {
                // Bug 2 修复: force-stop 弹窗直接毁掉无障碍服务, 不能再要求 isOurApp。
                // 四路 predicate 命中即拦截, 统一走 handleDetection (复用共享 5 秒热窗 + 退出策略)。
                Log.w(TAG, "!!! [Force Stop] Force stop dialog detected! Blocking. Pkg=$currentPackage Class=$fsEventClassName")
                logBlock(
                    reason = "FORCE_STOP_DIALOG",
                    pkg = currentPackage,
                    cls = fsEventClassName,
                    context = "dialog",
                    sample = fsEventText
                )
                LogExporter.logEvent(TAG, "[BLOCK] Force Stop dialog detected! Pkg=$currentPackage Class=$fsEventClassName Text=$fsEventText")
                handleDetection(
                    service = service,
                    pkg = currentPackage,
                    isAccessibilityContext = false,
                    reason = "FORCE_STOP_DIALOG",
                    details = "class=$fsEventClassName"
                )
                return
            }
        }

        // 1. Check Protection State (for anti-uninstall + app info protections)
        // Privacy-space interception is now an independent gate.
        // The anti-uninstall state alone no longer pre-empts the second suite.
        val antiUninstallEnforcing: Boolean = try {
            AntiUninstallManager.getProtectionState(context) != AntiUninstallManager.State.DISABLED
        } catch (e: Exception) {
            // Manager exception: keep "do not close protection chain" behavior —
            // treat anti-uninstall as still enforcing so the chain continues.
            true
        }
        val privacySpaceEnforcing = PrivacyProtectionManager.isEnforcing(context)

        // Both gates off → return early (whole controlled region exits).
        if (!antiUninstallEnforcing && !privacySpaceEnforcing) {
            return
        }

        // Anti-uninstall OFF but privacy ON → keep existing debug message
        // without returning early (privacy suite is still authoritative here).
        if (!antiUninstallEnforcing) {
            Log.d(TAG, "[Skip] Protection disabled")
        }

        // 馃攳 [Internal Log] 璁板綍浜嬩欢鍒板唴瀛樼紦鍐插尯锛堢敤浜庢棩蹇楀鍑猴級
        // Diagnostic event log is gated by anti-uninstall execution; the privacy
        // toggle MUST NOT authorize this log.
        if (antiUninstallEnforcing &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            shouldLogEvent(currentPackage, now)) {
            LogExporter.logEvent(TAG, "[Event] Pkg=$currentPackage Class=${event.className} Text=${event.text}")
        }

        // 1. Safe App Checks
        val isSafePackage = SAFE_PACKAGES_KEYWORDS.any { currentPackage.contains(it, ignoreCase = true) }
        val isSafeApp = isSafePackage || currentPackage == context.packageName
        if (isSafeApp) return

        val rootPackage = root.packageName?.toString()
        if (rootPackage == context.packageName) return

        // Privacy-space second suite: only when privacy toggle is enforcing.
        // refreshPrivateSpaceSession must still run before shouldBlockPrivateSpaceAddApp
        // (R-09 session-refresh ordering preserved).
        if (privacySpaceEnforcing) {
            refreshPrivateSpaceSession(currentPackage, rootPackage ?: "", now)

            if (shouldBlockPrivateSpaceAddApp(currentPackage, event, root)) {
                val eventClass = event.className?.toString() ?: ""
                val sampleText = lastPrivateSpaceMatchedSample ?: buildString {
                    append(event.text?.toString() ?: "")
                    append(" ")
                    val sb = StringBuilder()
                    collectAllText(root, sb, maxDepth = 4, maxChildren = 14)
                    append(sb.toString())
                }.trim()
                lastPrivateSpaceMatchedSample = null
                logBlock(
                    reason = "PRIVATE_SPACE_ADD_APP",
                    pkg = currentPackage,
                    cls = eventClass,
                    context = "privateSpace",
                    sample = sampleText
                )
                mLastBlockTime = System.currentTimeMillis()
                handleDetection(
                    service = service,
                    pkg = currentPackage,
                    isAccessibilityContext = false,
                    reason = "PRIVATE_SPACE_ADD_APP",
                    details = "class=$eventClass"
                )
                return
            }

            // Privacy suite missed AND anti-uninstall OFF → no further work.
            if (!antiUninstallEnforcing) return
        }

        // 馃敀 [App Info Page Interception]
        // Only settings package - app info and permission pages
        val isSettingsPackage = currentPackage == "com.android.settings"
                                || currentPackage.contains("settings", ignoreCase = true)

        if (isSettingsPackage && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventClassName = event.className?.toString() ?: ""
            val eventText = event.text?.toString() ?: ""

            val isVivoSubSettings = eventClassName.contains("VivoSubSettings", ignoreCase = true)
            if (isVivoSubSettings) {
                val sb = StringBuilder()
                collectAllText(root, sb)
                val pageContent = sb.toString()
                val hasAccessibilityContext = ACCESSIBILITY_CONTEXT_KEYWORDS.any {
                    pageContent.contains(it, ignoreCase = true)
                }
                val hasAppInfoIndicators = APP_INFO_INDICATORS.any {
                    pageContent.contains(it, ignoreCase = true)
                }
                if (hasAccessibilityContext && !hasAppInfoIndicators) {
                    LogExporter.logEvent(TAG, "[Skip] Accessibility context on VivoSubSettings, ignore app-info interception")
                    return
                }
            }
            
            // App info page detection.
            val isAppInfoPage = eventClassName.contains("InstalledAppDetails", ignoreCase = true) ||  // includes InstalledAppDetailsTop
                                eventClassName.contains("AppInfoDashboardFragment", ignoreCase = true) ||
                                eventClassName.contains("AppInfoBase", ignoreCase = true) ||
                                eventClassName.contains("AppInfo", ignoreCase = true) ||
                                eventClassName.contains("VivoSubSettings", ignoreCase = true)  // Vivo 璁剧疆瀛愰〉闈?            
            // 绛栫暐2a: event.text 涓洿鎺ュ寘鍚垜浠殑搴旂敤鍚?(濡?Text=[IronWill] 鎴?Text=[搴旂敤淇℃伅])
            val hasOurAppInEventText = PROTECTED_APP_NAMES.any { eventText.contains(it, ignoreCase = true) }

            val canBlockByEventText = !isVivoSubSettings
            if (isAppInfoPage && hasOurAppInEventText && canBlockByEventText) {
                armUninstallHotWindow(now, "APP_INFO_EVENT_TEXT")
                Log.w(TAG, "!!! [App Info] IronWill 鍦ㄤ簨浠舵枃鏈腑琚彂鐜? 绔嬪嵆鎷︽埅鏁翠釜椤甸潰. Class=$eventClassName Text=$eventText")
                logBlock(
                    reason = "APP_INFO_EVENT_TEXT",
                    pkg = currentPackage,
                    cls = eventClassName,
                    context = "settingsAppInfo",
                    sample = eventText
                )
                LogExporter.logEvent(TAG, "[BLOCK] App Info page (event text match) for IronWill! Class=$eventClassName")
                mLastBlockTime = System.currentTimeMillis()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
            if (isAppInfoPage && hasOurAppInEventText && isVivoSubSettings) {
                armUninstallHotWindow(now, "APP_INFO_VIVO_EVENT_TEXT")
                LogExporter.logEvent(TAG, "[Skip] VivoSubSettings event text only, waiting for deep-scan confirmation")
            }
            
            // 绛栫暐2b: 娣卞害鎵弿椤リエ潰鍐呭锛屾鏌ユ槸鍚︽槸 IronWill 鐨勫簲鐢ㄤ俊鎭〉
            // 鈿狅笍 淇敼锛氬彧瑕佹娴嬪埌 IronWill 灏辨嫤鎴紝涓嶅啀闇€瑕佹娴嬬壒瀹氭寜閽?
            if (isAppInfoPage) {
                val sb = StringBuilder()
                collectAllText(root, sb)
                val pageContent = sb.toString()
                
                val isOurApp = PROTECTED_APP_NAMES.any { pageContent.contains(it, ignoreCase = true) }
                                || pageContent.contains("com.ironwill", ignoreCase = true)
                                || pageContent.contains("ironwill", ignoreCase = true)
                
                // 馃敀 鍙鏄?IronWill 鐨勫簲鐢ㄤ俊鎭〉灏辨嫤鎴紙鍚潈闄愰〉銆佸瓨鍌ㄩ〉绛夋墍鏈夊瓙椤甸潰锛?                // 鍥犱负鐢ㄦ埛鍙互閫氳繃"鏌ョ湅鎵€鏈夋潈闄?鍏抽棴鏃犻殰纰嶆潈闄愶紝蹇呴』鎷︽埅鏁翠釜椤甸潰
                val hasAccessibilityContext = ACCESSIBILITY_CONTEXT_KEYWORDS.any {
                    pageContent.contains(it, ignoreCase = true)
                }
                val hasAppInfoIndicators = APP_INFO_INDICATORS.any {
                    pageContent.contains(it, ignoreCase = true)
                }
                if (isOurApp && AccessibilityProtection.isAccessibilityServiceListContent(pageContent)) {
                    LogExporter.logEvent(TAG, "[Skip] Settings accessibility service list")
                    return
                }
                // [Gate] 防关权限保护 ACTIVE 或 PENDING 时才拦截无障碍详情页
                // PENDING 表示已申请关闭但次日才生效，生效前仍需保护
                if (isOurApp && AccessibilityProtection.isIronWillAccessibilityDetailContent(pageContent)) {
                    val a11yState = AccessibilityControlManager.getState(context)
                    if (a11yState != AccessibilityControlManager.State.ACTIVE &&
                        a11yState != AccessibilityControlManager.State.PENDING) {
                        LogExporter.logEvent(TAG, "[Skip] 防关权限保护未开启，跳过无障碍详情页拦截")
                        return
                    }
                }
                val shouldBlockCurrentPage = !isVivoSubSettings || (hasAppInfoIndicators && !hasAccessibilityContext)

                if (isOurApp && shouldBlockCurrentPage) {
                    armUninstallHotWindow(now, "APP_INFO_DEEP_SCAN")
                    Log.w(TAG, "!!! [App Info] IronWill 鐨勫簲鐢ㄤ俊鎭〉琚墦寮€! 绔嬪嵆鎷︽埅鏁翠釜椤リエ潰. Class=$eventClassName Content=${pageContent.take(200)}")
                    logBlock(
                        reason = "APP_INFO_DEEP_SCAN",
                        pkg = currentPackage,
                        cls = eventClassName,
                        context = "settingsAppInfo",
                        sample = pageContent
                    )
                    LogExporter.logEvent(TAG, "[BLOCK] App Info page (deep scan) for IronWill! Class=$eventClassName")
                    mLastBlockTime = System.currentTimeMillis()
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    return
                }
                if (isOurApp && isVivoSubSettings) {
                    LogExporter.logEvent(TAG, "[Skip] VivoSubSettings deep scan matched app name without app-info indicators")
                }
            }
            
            // 绛栫暐3: 妫€娴嬫潈闄愮鐞嗛〉闈紙鍦?settings 鍖呭唴鐨勬潈闄?Activity锛?
            val isPermissionPage = eventClassName.contains("Permission", ignoreCase = true) ||
                                   eventClassName.contains("AppPermission", ignoreCase = true) ||
                                   eventClassName.contains("ManagePermissions", ignoreCase = true)
            
            if (isPermissionPage) {
                val sb = StringBuilder()
                collectAllText(root, sb)
                val pageContent = sb.toString()
                
                val isOurApp = PROTECTED_APP_NAMES.any { pageContent.contains(it, ignoreCase = true) }
                                || pageContent.contains("com.ironwill", ignoreCase = true)
                
                if (isOurApp) {
                    Log.w(TAG, "!!! [Permission] IronWill 鐨勬潈闄愰〉闈㈣鎵撳紑! 绔嬪嵆鎷︽埅. Class=$eventClassName")
                    logBlock(
                        reason = "PERMISSION_PAGE_DEEP_SCAN",
                        pkg = currentPackage,
                        cls = eventClassName,
                        context = "settingsPermission",
                        sample = pageContent
                    )
                    LogExporter.logEvent(TAG, "[BLOCK] Permission page for IronWill! Class=$eventClassName")
                    mLastBlockTime = System.currentTimeMillis()
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    return
                }
            }
            
            // [App Info Tracking] Track for delayed CONTENT_CHANGED scans.
            if (isAppInfoPage) {
                mInAppInfoPagePackage = currentPackage
                mInAppInfoPageClass = eventClassName
                mInAppInfoPageTime = System.currentTimeMillis()
                if (hasOurAppInEventText) {
                    armUninstallHotWindow(now, "APP_INFO_TRACKING")
                }
                Log.d(TAG, "[Track] Enter app info page, start CONTENT_CHANGED tracking. Class=$eventClassName")
            }
        }
        
        // 馃敀 [App Info CONTENT_CHANGED 寤惰繜妫€娴媇
        // 瑙ｅ喅鏍稿績闂锛欼nstalledAppDetailsTop 鐨?STATE_CHANGED 浜嬩欢瑙﹀彂鏃堕〉闈㈠唴瀹瑰皻鏈姞杞藉畬鎴愶紝
        // 娣卞害鎵弿鎵句笉鍒?"IronWill"銆傞渶瑕佸湪鍚庣画鐨?CONTENT_CHANGED 浜嬩欢涓噸鏂版娴嬨€?
        val isInAppInfoTracking = mInAppInfoPagePackage != null
            && currentPackage == mInAppInfoPagePackage
            && System.currentTimeMillis() - mInAppInfoPageTime < APP_INFO_PAGE_TIMEOUT
        if (isInAppInfoTracking && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val sb = StringBuilder()
            collectAllText(root, sb)
            val pageContent = sb.toString()
            
            val isOurApp = PROTECTED_APP_NAMES.any { pageContent.contains(it, ignoreCase = true) }
                            || pageContent.contains("com.ironwill", ignoreCase = true)
                            || pageContent.contains("ironwill", ignoreCase = true)
            if (isOurApp && AccessibilityProtection.isAccessibilityServiceListContent(pageContent)) {
                LogExporter.logEvent(TAG, "[Skip] Delayed accessibility service list")
                return
            }

            val isTrackedVivoSubSettings = mInAppInfoPageClass?.contains("VivoSubSettings", ignoreCase = true) == true
            val hasAccessibilityContext = ACCESSIBILITY_CONTEXT_KEYWORDS.any {
                pageContent.contains(it, ignoreCase = true)
            }
            val hasAppInfoIndicators = APP_INFO_INDICATORS.any {
                pageContent.contains(it, ignoreCase = true)
            }
            val shouldBlockDelayed = !isTrackedVivoSubSettings || (hasAppInfoIndicators && !hasAccessibilityContext)

            if (isOurApp && shouldBlockDelayed) {
                armUninstallHotWindow(now, "APP_INFO_CONTENT_DELAYED")
                Log.w(TAG, "!!! [App Info CONTENT] IronWill 鐨勫簲鐢ㄤ俊鎭〉鍐呭宸插姞杞? 绔嬪嵆鎷︽埅. Content=${pageContent.take(200)}")
                logBlock(
                    reason = "APP_INFO_CONTENT_DELAYED",
                    pkg = currentPackage,
                    cls = event.className?.toString(),
                    context = "settingsAppInfoDelayed",
                    sample = pageContent
                )
                LogExporter.logEvent(TAG, "[BLOCK] App Info page (CONTENT_CHANGED delayed scan) for IronWill!")
                mInAppInfoPagePackage = null
                mInAppInfoPageClass = null
                mLastBlockTime = System.currentTimeMillis()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
        }
        
        // When leaving settings, clear app-info tracking.
        if (mInAppInfoPagePackage != null && !currentPackage.contains("settings", ignoreCase = true)
            && currentPackage != mInAppInfoPagePackage) {
            mInAppInfoPagePackage = null
            mInAppInfoPageClass = null
        }

        // Safe Context Check (Search History)
        val contentText = event.text.toString()
        if (SEARCH_HISTORY_KEYWORDS.any { contentText.contains(it, ignoreCase = true) }) {
             return
        }
        
        // 馃敀 [Safe Context] 鏃犻殰纰嶈缃鎯呴〉妫€娴?        // 濡傛灉鍦ㄦ椤甸潰妫€娴嬪埌鍗歌浇/鍋滅敤鎰忓浘锛屾垜浠渶瑕佺壒娈婂鐞嗛€€鍑洪€昏緫
        val isAccessibilityContext = ACCESSIBILITY_CONTEXT_KEYWORDS.any { contentText.contains(it, ignoreCase = true) }
        if (isAccessibilityContext) {
            Log.d(TAG, "[Context] Accessibility settings context detected. Will use enhanced exit strategy.")
        }

        // 2. Debounce (Anti-ANR but fast enough for protection)
        // 馃殌 [Fix] STATE_CHANGED events (dialog popup) bypass debounce for instant detection
        val isDialogEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isDialogEvent && now - mLastBlockTime < 500) return // 500ms debounce for content changes only

        // 3. Detection Logic
        if (ProtectionManager.DEBUG) {
            Log.d(TAG, "[Scan] Reached detection: Pkg=$currentPackage isVivo=${VIVO_LAUNCHER_PACKAGES.contains(currentPackage)} isMonitored=${MONITORED_PACKAGES.any { currentPackage.contains(it) }}")
        }
        
        var detected = false
        val isVivoLauncher = VIVO_LAUNCHER_PACKAGES.contains(currentPackage)
        var blockReason: String? = null
        var blockDetails: String? = null
        
        if (isVivoLauncher) {
             // 馃殌 [Vivo Launcher] Detection Strategy:
             // Vivo long-press menu shows: [鍗歌浇] [搴旂敤淇℃伅] [鍒嗕韩]
             // When user clicks "鍗歌浇", a confirmation dialog pops up with [鐩存帴鍗歌浇] [鍙栨秷] etc.
             // 
             // KEY INSIGHT: The long-press MENU and the confirmation DIALOG are different UI states.
             // - Long-press Menu: TYPE_WINDOW_STATE_CHANGED with PopupWindow/ListView class
             // - Confirmation Dialog: TYPE_WINDOW_STATE_CHANGED with AlertDialog class
             //
             // STRATEGY: 
             //   1. If we see a long-press menu (has 鍒嗕韩/搴旂敤淇℃伅 alongside 鍗歌浇) 鈫?ALLOW (don't block)
             //   2. If we see a confirmation dialog (has 纭畾/鍙栨秷/鐩存帴鍗歌浇) 鈫?BLOCK
             //   3. The menu itself is harmless; only the CONFIRMATION is dangerous.
             
             val eventText = event.text.toString()
             val eventClass = event.className?.toString() ?: ""

             // [ARM] Long-press menu pre-arming:
             // If we detect launcher menu context for protected app, keep a short-lived armed state.
             // Then confirmation dialogs lacking explicit app name can still be blocked via fast-path.
             armVivoLongPressMenuContext(eventText, eventClass, root, now)
             val hasArmedMenuContext = isVivoMenuArmActive(now)

             // 高频内容变更噪声快速跳过：非对话框且未 ARM 时，不进入深扫，避免卡顿与时延抖动。
             if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                 !hasArmedMenuContext &&
                 !eventClass.contains("dialog", ignoreCase = true) &&
                 !eventClass.contains("alert", ignoreCase = true)) {
                 return
             }

             // Fast path: Vivo confirms often expose enough signal in event text.
             // Block immediately to avoid deep-scan latency windows.
             val canUseFastPathEvent =
                 event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                     event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
             if (canUseFastPathEvent && isVivoDialogFastPath(eventClass, eventText, hasArmedMenuContext)) {
                 Log.w(TAG, "!!! [Vivo] Fast-path confirmation dialog detected. Blocking immediately.")
                 LogExporter.logEvent(TAG, "[BLOCK] Vivo fast-path dialog hit. Class=$eventClass")
                 blockReason = if (hasArmedMenuContext) "VIVO_DIALOG_FAST_PATH_ARMED" else "VIVO_DIALOG_FAST_PATH"
                 blockDetails = "class=$eventClass armed=$hasArmedMenuContext"
                 logBlock(
                     reason = blockReason ?: "VIVO_DIALOG_FAST_PATH",
                     pkg = currentPackage,
                     cls = eventClass,
                     context = "launcherFastPath",
                     sample = eventText,
                     extras = blockDetails
                 )
                 detected = true
             }

             if (detected) {
                 mLastBlockTime = now
                 handleDetection(
                     service,
                     currentPackage,
                     isAccessibilityContext,
                     blockReason ?: "UNKNOWN",
                     blockDetails ?: "n/a"
                 )
                 return
             }
             
             // 1. Fast Filter: Check if this event is relevant at all
             val hasUninstallInEvent = UNINSTALL_KEYWORDS.any { eventText.contains(it) }
             val hasAppNameInEvent = PROTECTED_APP_NAMES.any { eventText.contains(it, ignoreCase = true) }
             
             val isSuspicious = hasUninstallInEvent || hasAppNameInEvent || 
                                eventClass.contains("Dialog", ignoreCase = true) ||
                                eventClass.contains("Alert", ignoreCase = true)
             
             if (!isSuspicious) {
                 return // Safe event (scrolling, swiping, etc.)
             }
             
             // 2. Deep Scan
             val sb = StringBuilder()
             sb.append(eventText).append(" ") 
             collectAllText(root, sb, maxDepth = 6, maxChildren = 18)
             val combinedText = sb.toString()
             
             Log.d(TAG, "[Vivo Scan] combinedText=$combinedText")
             Log.d(TAG, "[Vivo Scan] eventClass=$eventClass")

             val hasAppName = PROTECTED_APP_NAMES.any { combinedText.contains(it, ignoreCase = true) }
             val hasUninstall = UNINSTALL_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }
             
             // 3. Distinguish Long-Press Menu vs Confirmation Dialog
             // Long-press menu contains MULTIPLE action items together: 鍗歌浇 + 鍒嗕韩 + 搴旂敤淇℃伅
             // Confirmation dialog contains: 鍗歌浇 + (纭畾/鐩存帴/鍙栨秷)
             val isLongPressMenu = SAFE_MENU_KEYWORDS.any { combinedText.contains(it, ignoreCase = true) }
             
             // Confirmation keywords (buttons in a real uninstall dialog)
             // 馃殌 [Fix] Enhanced English Support: Add "Uninstall", "Delete", "Yes" as confirmation actions.
             val confirmKeywords = listOf(
                 "纭畾", "纭", "鐩存帴鍗歌浇", "鐩存帴", "纰哄畾", "纰鸿獚", "瑙ｉ櫎瀹夎",
                 "Confirm", "OK", "Yes", "Uninstall", "Delete", "Remove"
             )
             val cancelKeywords = listOf("鍙栨秷", "Cancel", "Annuler", "Cancelar", "Abbrechen", "No")
             
             // Case-insensitive check for buttons
             val hasConfirm = confirmKeywords.any { combinedText.contains(it, ignoreCase = true) }
             val hasCancel = cancelKeywords.any { combinedText.contains(it, ignoreCase = true) }
             
             // 馃敀 鍒ゅ畾鏄惁涓哄嵏杞界‘璁ゅ璇濇鐨勬牳蹇冮€昏緫:
             // - 鏂瑰紡1: Dialog 绫诲悕 + 鍗歌浇鍏抽敭璇?(濡?com.originui.widget.dialog.d 寮瑰嚭"鍗歌浇IronWill锛?)
             //   Vivo 纭妗嗘枃鏈? [鍗歌浇"IronWill"锛? "IronWill"鍙婂叾鏁版嵁灏嗕細琚垹闄ゃ€? 鍗歌浇, 鍙栨秷]
             // - 鏂瑰紡2: 鏈夌‘璁ゆ寜閽?(纭畾/鐩存帴鍗歌浇)
             // 鈿狅笍 鍏抽敭: 褰?isLongPressMenu=true 鏃? "鍙栨秷"鎸夐挳涓嶈兘鍗曠嫭鍒ゅ畾涓虹‘璁ゅ璇濇
             //   鍥犱负鍒嗕韩闈㈡澘绛夋甯窾I涔熸湁鍙栨秷鎸夐挳, 浼氳鎷︽埅!
             val isDialogClassEvent = eventClass.contains("dialog", ignoreCase = true) ||
                                      eventClass.contains("alert", ignoreCase = true)
             val isConfirmationDialog = hasConfirm ||
                                        (isDialogClassEvent && hasCancel) ||  // Dialog 绫诲悕 + 鍙栨秷 = 纭妗?
                                        (!isLongPressMenu && hasCancel)       // 鏃犺彍鍗曠壒寰?+ 鍙栨秷 = 纭妗?
             if (hasAppName && hasUninstall) {
                 if (isLongPressMenu && !isConfirmationDialog) {
                     // This is the long-press popup menu with [鍗歌浇][鍒嗕韩][搴旂敤淇℃伅]
                     // 鎴栬€呯敤鎴风偣浜?鍒嗕韩"鍚庡脊鍑轰簡鍒嗕韩闈㈡澘锛堜粛鏈夎彍鍗曠壒寰佽瘝锛屼絾鏃犵‘璁ょ被鍚嶏級
                     // Do NOT block - 鍏佽鍒嗕韩绛夋甯告搷浣?
                     Log.d(TAG, "[Vivo Scan] Long-press menu or share panel detected (has Share/AppInfo, no Dialog class). Allowing.")
                 } else if (isConfirmationDialog) {
                     // This is a real confirmation dialog with 纭畾/鐩存帴鍗歌浇锛屾垨鑰?Dialog 绫诲悕+鍙栨秷
                     Log.w(TAG, "!!! [Vivo] Uninstall CONFIRMATION dialog detected !!! Blocking. isDialogClass=$isDialogClassEvent")
                     LogExporter.logEvent(TAG, "[BLOCK] Vivo confirmation dialog. Confirm=$hasConfirm Cancel=$hasCancel DialogClass=$isDialogClassEvent")
                    blockReason = "VIVO_CONFIRM_DIALOG"
                    blockDetails = "hasConfirm=$hasConfirm hasCancel=$hasCancel isDialogClass=$isDialogClassEvent isLongPressMenu=$isLongPressMenu"
                    logBlock(
                        reason = blockReason ?: "VIVO_CONFIRM_DIALOG",
                        pkg = currentPackage,
                        cls = eventClass,
                        context = "launcher",
                        sample = combinedText,
                        extras = blockDetails
                    )
                     detected = true
                 } else {
                     // Ambiguous: has Uninstall + AppName but no menu items and no confirm/cancel
                     // Could be a transitional state or a simple uninstall prompt without buttons yet
                     // Be conservative: block it (better safe than sorry)
                     Log.w(TAG, "!!! [Vivo] Uninstall detected (no menu, no confirm). Blocking as precaution.")
                     LogExporter.logEvent(TAG, "[BLOCK] Vivo ambiguous uninstall (no menu, no confirm)")
                    blockReason = "VIVO_AMBIGUOUS_UNINSTALL"
                    blockDetails = "hasUninstall=$hasUninstall hasAppName=$hasAppName isLongPressMenu=$isLongPressMenu"
                    logBlock(
                        reason = blockReason ?: "VIVO_AMBIGUOUS_UNINSTALL",
                        pkg = currentPackage,
                        cls = eventClass,
                        context = "launcher",
                        sample = combinedText,
                        extras = blockDetails
                    )
                     detected = true
                 }
             } else {
                 Log.d(TAG, "[Vivo Scan] Safe. Name=$hasAppName Uninstall=$hasUninstall")
             }
        } else {
            // Generic Handling
            // CRITICAL FIX: Only scan if it is a MONITORED package.
            // This excludes generic launchers from the "Uninstall" keyword scan.
            
            val isMonitoredPackage = MONITORED_PACKAGES.any { currentPackage.contains(it) }
            val eventText = event.text.toString()
            val eventClass = event.className?.toString() ?: ""
            val isHotWindow = isUninstallHotWindowActive(now)
            val isStartupWindow = AccessibilityProtection.isInStartupGrace(now)

            if (isMonitoredPackage && isMonitoredDialogFastPath(currentPackage, eventClass, eventText, isHotWindow, isStartupWindow)) {
                LogExporter.logEvent(TAG, "[BLOCK] Monitored package fast-path hit. Pkg=$currentPackage Class=$eventClass hot=$isHotWindow startup=$isStartupWindow")
                blockReason = when {
                    isHotWindow -> "MONITORED_DIALOG_FAST_PATH_HOT"
                    isStartupWindow -> "MONITORED_DIALOG_FAST_PATH_STARTUP"
                    else -> "MONITORED_DIALOG_FAST_PATH"
                }
                blockDetails = "class=$eventClass hot=$isHotWindow startup=$isStartupWindow"
                logBlock(
                    reason = blockReason ?: "MONITORED_DIALOG_FAST_PATH",
                    pkg = currentPackage,
                    cls = eventClass,
                    context = "monitoredFastPath",
                    sample = eventText,
                    extras = blockDetails
                )
                detected = true
            }
            
            if (isMonitoredPackage && !detected) {
                val uninstallSample = detectUninstallAttemptSample(root, currentPackage)
                if (uninstallSample != null) {
                    Log.w(TAG, "!!! [Global Scan] 鎹曡幏鍒板嵏杞藉皾璇? $currentPackage !!!")
                    LogExporter.logEvent(TAG, "[BLOCK] Global scan detected uninstall in $currentPackage")
                    blockReason = "GLOBAL_UNINSTALL_SCAN"
                    blockDetails = "monitoredPkg=$currentPackage"
                    logBlock(
                        reason = blockReason ?: "GLOBAL_UNINSTALL_SCAN",
                        pkg = currentPackage,
                        cls = event.className?.toString(),
                        context = "monitoredPkg",
                        sample = uninstallSample,
                        extras = blockDetails
                    )
                    detected = true
                }
            }
        }

        if (detected) {
            mLastBlockTime = now
            handleDetection(
                service,
                currentPackage,
                isAccessibilityContext,
                blockReason ?: "UNKNOWN",
                blockDetails ?: "n/a"
            )
        }
    }

    private fun isVivoDialogFastPath(eventClass: String, eventText: String, hasArmedMenuContext: Boolean): Boolean {
        val isDialogClassEvent = eventClass.contains("dialog", ignoreCase = true) ||
            eventClass.contains("alert", ignoreCase = true)
        if (!isDialogClassEvent) return false

        val hasUninstall = UNINSTALL_KEYWORDS.any { eventText.contains(it, ignoreCase = true) }
        if (!hasUninstall) return false

        val hasProtectedName = PROTECTED_APP_NAMES.any { eventText.contains(it, ignoreCase = true) } ||
            eventText.contains("com.ironwill", ignoreCase = true)
        if (hasProtectedName) return true

        if (!hasArmedMenuContext) return false
        val hasDecisionButton = eventText.contains("取消", ignoreCase = true) ||
            eventText.contains("cancel", ignoreCase = true) ||
            eventText.contains("确定", ignoreCase = true) ||
            eventText.contains("确认", ignoreCase = true) ||
            eventText.contains("ok", ignoreCase = true)
        return hasDecisionButton
    }

    private fun isVivoMenuArmActive(now: Long): Boolean {
        return now - mVivoMenuArmTime <= VIVO_MENU_ARM_TTL_MS
    }

    private fun isUninstallHotWindowActive(now: Long): Boolean {
        return now <= mUninstallHotWindowUntil
    }

    private fun armUninstallHotWindow(now: Long, source: String) {
        val newUntil = now + UNINSTALL_HOT_WINDOW_MS
        if (newUntil <= mUninstallHotWindowUntil) return
        mUninstallHotWindowUntil = newUntil
        LogExporter.logEvent(TAG, "[ARM] Uninstall hot window armed (${UNINSTALL_HOT_WINDOW_MS}ms) source=$source")
    }

    private fun isMonitoredDialogFastPath(
        pkg: String,
        eventClass: String,
        eventText: String,
        isHotWindow: Boolean,
        isStartupWindow: Boolean
    ): Boolean {
        val isInstallerLikePkg =
            pkg.contains("packageinstaller", ignoreCase = true) ||
                pkg == "com.android.settings"
        if (!isInstallerLikePkg) return false

        val isDialogClass = eventClass.contains("dialog", ignoreCase = true) ||
            eventClass.contains("alert", ignoreCase = true)
        if (!isDialogClass) return false

        val hasUninstall = UNINSTALL_KEYWORDS.any { eventText.contains(it, ignoreCase = true) }
        if (!hasUninstall) return false

        val hasProtectedName = PROTECTED_APP_NAMES.any { eventText.contains(it, ignoreCase = true) } ||
            eventText.contains("com.ironwill", ignoreCase = true)
        if (hasProtectedName) return true

        if (!isHotWindow && !isStartupWindow) return false
        val hasDecisionButton = eventText.contains("取消", ignoreCase = true) ||
            eventText.contains("cancel", ignoreCase = true) ||
            eventText.contains("确定", ignoreCase = true) ||
            eventText.contains("确认", ignoreCase = true) ||
            eventText.contains("ok", ignoreCase = true)
        return hasDecisionButton
    }

    /**
     * 前置条件：
     * 1. 已进入隐私系统会话。
     * 2. 当前页被轻量识别为“添加应用”链路（如 AppMoveInActivity/添加应用标志节点）。
     *
     * 仅在满足以上条件时才允许执行深层无障碍树抓取，避免在其他隐私页做高成本扫描。
     */
    private fun shouldBlockPrivateSpaceAddApp(
        currentPackage: String,
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo
    ): Boolean {
        lastPrivateSpaceMatchedSample = null
        val now = System.currentTimeMillis()
        val eventClass = event.className?.toString() ?: ""
        val eventText = event.text?.toString() ?: ""
        val rootPackage = root.packageName?.toString() ?: ""

        val inPrivateSpaceSession = isPrivateSpaceSessionActive(now)
        if (!inPrivateSpaceSession) return false

        val pageHint = StringBuilder().apply {
            append(eventText).append(" ")
            collectPrivateSpacePageHints(root, this, maxDepth = 2, maxChildren = 18)
        }.toString()
        val hasDirectAddAppSignal = isPrivateSpaceAddAppPageCandidate(
            inPrivateSpaceSession = inPrivateSpaceSession,
            currentPackage = currentPackage,
            rootPackage = rootPackage,
            eventClass = eventClass,
            hintContent = pageHint
        )
        if (hasDirectAddAppSignal) {
            armPrivateSpaceAddAppPageContext(currentPackage, rootPackage, now)
        }
        val hasRecentAddAppPage = isPrivateSpaceAddAppPageContextActive(currentPackage, rootPackage, now)
        val isAddAppPage = isPrivateSpaceAddAppPageCandidate(
            inPrivateSpaceSession = inPrivateSpaceSession,
            currentPackage = currentPackage,
            rootPackage = rootPackage,
            eventClass = eventClass,
            hintContent = pageHint,
            hasRecentConfirmedAddAppPage = hasRecentAddAppPage
        )
        if (!isAddAppPage) {
            maybeLogPrivateSpaceSkipScrape(currentPackage, rootPackage, eventClass, pageHint)
            return false
        }

        // 对同一页面短时重复事件去抖，避免 BACK+HOME 被连续触发。
        val signature = "$currentPackage|$rootPackage|$eventClass"
        if (signature == lastPrivateSpaceBlockSignature && now - lastPrivateSpaceBlockAt < 1200L) {
            return false
        }
        lastPrivateSpaceBlockSignature = signature
        lastPrivateSpaceBlockAt = now
        return true
    }

    private fun refreshPrivateSpaceSession(currentPackage: String, rootPackage: String, now: Long) {
        val currentHit = PRIVATE_SPACE_PACKAGES.any { currentPackage.contains(it, ignoreCase = true) } ||
            PRIVATE_SPACE_PACKAGE_HINTS.any { currentPackage.contains(it, ignoreCase = true) }
        val rootHit = PRIVATE_SPACE_PACKAGES.any { rootPackage.contains(it, ignoreCase = true) } ||
            PRIVATE_SPACE_PACKAGE_HINTS.any { rootPackage.contains(it, ignoreCase = true) }
        val hitPrivatePkg = currentHit || rootHit
        if (!hitPrivatePkg) return

        privateSpaceSessionUntil = now + PRIVATE_SPACE_SESSION_TTL_MS
        val sourcePkg = when {
            rootHit && rootPackage.isNotBlank() -> rootPackage
            currentPackage.isNotBlank() -> currentPackage
            else -> rootPackage
        }
        if (sourcePkg != privateSpaceLastSourcePkg) {
            privateSpaceLastSourcePkg = sourcePkg
            LogExporter.logEvent(TAG, "[PRIVATE_SPACE] Entered/active via pkg=$sourcePkg")
        }
    }

    private fun isPrivateSpaceSessionActive(now: Long): Boolean {
        return now <= privateSpaceSessionUntil
    }

    private fun armPrivateSpaceAddAppPageContext(currentPackage: String, rootPackage: String, now: Long) {
        privateSpaceAddAppPageUntil = now + PRIVATE_SPACE_ADD_APP_PAGE_TTL_MS
        privateSpaceAddAppPagePkg = currentPackage.takeIf { it.isNotBlank() }
        privateSpaceAddAppPageRootPkg = rootPackage.takeIf { it.isNotBlank() }
    }

    private fun isPrivateSpaceAddAppPageContextActive(
        currentPackage: String,
        rootPackage: String,
        now: Long
    ): Boolean {
        if (now > privateSpaceAddAppPageUntil) return false
        val activePkgs = listOfNotNull(privateSpaceAddAppPagePkg, privateSpaceAddAppPageRootPkg)
        if (activePkgs.isEmpty()) return false
        val currentCandidates = listOf(currentPackage, rootPackage).filter { it.isNotBlank() }
        return currentCandidates.any { candidate ->
            activePkgs.any { active -> candidate.equals(active, ignoreCase = true) }
        }
    }

    private fun containsProtectedAppToken(content: String): Boolean {
        return containsProtectedAppToken(content, context.packageName)
    }

    private fun selectPrivateSpaceMatchedEvidence(
        pageContent: String,
        sourceContent: String,
        searchHitEvidence: String?
    ): String {
        return when {
            !searchHitEvidence.isNullOrBlank() -> normalizeSample(searchHitEvidence, 120)
            containsProtectedAppToken(sourceContent) -> normalizeSample(sourceContent, 120)
            else -> normalizeSample(pageContent, 120)
        }
    }

    private fun collectPrivateSpaceEventSourceText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        val source = event.source ?: return ""
        try {
            collectAllText(source, sb, maxDepth = 6, maxChildren = 40)

            var parent = source.parent
            var depth = 0
            while (parent != null && depth < 2 && sb.length <= 5000) {
                collectAllText(parent, sb, maxDepth = 2, maxChildren = 24)
                val nextParent = parent.parent
                parent.recycle()
                parent = nextParent
                depth++
            }
        } finally {
            source.recycle()
        }
        return sb.toString()
    }

    private fun findPrivateSpaceProtectedEvidence(root: AccessibilityNodeInfo, event: AccessibilityEvent): String? {
        findProtectedAppEvidenceByText(root)?.let { return it }

        val source = event.source ?: return null
        return try {
            findProtectedAppEvidenceByText(source)
        } finally {
            source.recycle()
        }
    }

    private fun findProtectedAppEvidenceByText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null

        val queries = linkedSetOf<String>().apply {
            addAll(PROTECTED_APP_NAMES)
            add(context.packageName)
            add("ironwill")
        }

        for (query in queries) {
            val matches = try {
                node.findAccessibilityNodeInfosByText(query)
            } catch (_: Exception) {
                emptyList()
            }
            if (matches.isEmpty()) continue

            try {
                val sample = buildString {
                    matches.take(4).forEach { match ->
                        if (!match.text.isNullOrBlank()) append(match.text).append(" ")
                        if (!match.contentDescription.isNullOrBlank()) append(match.contentDescription).append(" ")
                        if (!match.viewIdResourceName.isNullOrBlank()) append(match.viewIdResourceName).append(" ")
                    }
                }.trim()
                if (sample.isNotBlank()) return sample
            } finally {
                matches.forEach { match -> match.recycle() }
            }
        }
        return null
    }

    private fun maybeLogPrivateSpaceSkipScrape(
        currentPackage: String,
        rootPackage: String,
        eventClass: String,
        pageHint: String
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPrivateSpaceGateMissLogAt < 1200L) return
        lastPrivateSpaceGateMissLogAt = now
        val sample = normalizeSample(pageHint, 80)
        LogExporter.logEvent(
            TAG,
            "[PRIVATE_SPACE] Skip scrape: not add-app page pkg=$currentPackage root=$rootPackage class=$eventClass sample=\"$sample\""
        )
    }

    private fun maybeLogPrivateSpaceMiss(
        currentPackage: String,
        rootPackage: String,
        eventClass: String,
        pageContent: String,
        sourceContent: String
    ) {
        if (!isPrivateSpaceSessionActive(System.currentTimeMillis())) return
        val now = System.currentTimeMillis()
        if (now - lastPrivateSpaceMissLogAt < 1200L) return
        lastPrivateSpaceMissLogAt = now
        val rootSample = normalizeSample(pageContent, 60)
        val sourceSample = normalizeSample(sourceContent, 60)
        LogExporter.logEvent(
            TAG,
            "[PRIVATE_SPACE] Visible scan miss pkg=$currentPackage root=$rootPackage class=$eventClass rootSample=\"$rootSample\" sourceSample=\"$sourceSample\""
        )
    }

    private fun armVivoLongPressMenuContext(
        eventText: String,
        eventClass: String,
        root: AccessibilityNodeInfo,
        now: Long
    ) {
        val hasUninstallKeyword = UNINSTALL_KEYWORDS.any { eventText.contains(it, ignoreCase = true) }
        if (!hasUninstallKeyword) return

        val hasMenuKeywords = SAFE_MENU_KEYWORDS.any { eventText.contains(it, ignoreCase = true) } ||
            eventClass.contains("list", ignoreCase = true) ||
            eventClass.contains("popup", ignoreCase = true)
        if (!hasMenuKeywords) return

        var hasProtectedName = PROTECTED_APP_NAMES.any { eventText.contains(it, ignoreCase = true) } ||
            eventText.contains("com.ironwill", ignoreCase = true)

        if (!hasProtectedName) {
            val sb = StringBuilder()
            sb.append(eventText).append(" ")
            collectAllText(root, sb, maxDepth = 4, maxChildren = 12)
            val combinedText = sb.toString()
            hasProtectedName = PROTECTED_APP_NAMES.any { combinedText.contains(it, ignoreCase = true) } ||
                combinedText.contains("com.ironwill", ignoreCase = true)
        }

        if (hasProtectedName) {
            mVivoMenuArmTime = now
            LogExporter.logEvent(TAG, "[ARM] Vivo long-press menu armed for protected app")
        }
    }

    private fun detectUninstallAttemptSample(root: AccessibilityNodeInfo, currentPackage: String): String? {
        val allText = StringBuilder()
        collectAllText(root, allText)
        val content = allText.toString()

        // 🚫 [A11y List Skip] If the page is an accessibility service list page,
        // never treat it as an uninstall attempt — even if "卸载" appears in nearby UI
        // elements. The list page is where users grant/revoke accessibility permissions
        // to ALL apps, and blocking it prevents other apps from requesting a11y access.
        if (AccessibilityProtection.isAccessibilityServiceListContent(content)) {
            return null
        }

        // 1. App Name Check - Also match package name pattern for App Info pages
        val hasAppName = PROTECTED_APP_NAMES.any { name -> content.contains(name, ignoreCase = true) }
                         || content.contains("com.ironwill", ignoreCase = true)
                         || content.contains("ironwill", ignoreCase = true)
        if (!hasAppName) return null

        // 2. Uninstall Keyword Check
        val hasUninstallKeyword = UNINSTALL_KEYWORDS.any { content.contains(it, ignoreCase = true) }
        if (hasUninstallKeyword) {
            Log.w(TAG, "Found Dangerous Context: $content")
            return content
        }

        return null
    }

    // 娉ㄦ剰锛歩sDialogShowing 鍜?hasPendingDialog 宸茬Щ鑷?companion object 渚涘叏灞€鍏变韩
    private var lastDialogShowTime = 0L
    private var lastDialogDismissTime = 0L
    private val MIN_DIALOG_INTERVAL_MS = 900L
    private val POST_DISMISS_DIALOG_COOLDOWN_MS = 400L
    private val WARNING_DIALOG_DELAY_MS = 80L
    private val ACCESSIBILITY_SECOND_BACK_DELAY_MS = 60L
    private val ACCESSIBILITY_HOME_DELAY_MS = 80L
    private val LAUNCHER_HOME_DELAY_MS = 20L
    private val LAUNCHER_REPEAT_SUPPRESS_MS = 180L
    
    // 馃攧 [寰呮樉绀哄脊绐楀幓閲峕 闃叉澶氫釜 Handler 浠诲姟鍫嗙Н
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingWarmupTask: Runnable? = null
    
    // 锟金煔€ [Performance] 缂撳瓨瀵硅瘽妗嗭紝閬垮厤姣忔鍒涘缓
    private var cachedDialog: android.app.AlertDialog? = null
    private var cachedDialogLocale: String? = null
    private var lastConfigResetLocale: String? = null
    private var lastConfigResetAt = 0L
    private val CONFIG_RESET_BURST_GUARD_MS = 1200L
    private val CONFIG_WARMUP_DELAY_MS = 600L
    
    // 馃殌 [Performance Metrics]
    private var scanStartTime = 0L

    private fun handleDetection(
        service: AccessibilityService,
        pkg: String,
        isAccessibilityContext: Boolean,
        reason: String,
        details: String
    ) {
        val now = System.currentTimeMillis()
        armUninstallHotWindow(now, "HANDLE_$reason")
        val signature = "$pkg|$reason|$details"
        
        val detectionTime = now - scanStartTime
        Log.w(TAG, "!!! 鎷︽埅鍒伴槻鍗歌浇鍔ㄤ綔 ($pkg) !!!")
        LogExporter.logEvent(
            TAG,
            "[HANDLE] Anti-uninstall triggered for $pkg reason=$reason details=$details (latency=${detectionTime}ms isAcc=$isAccessibilityContext)"
        )
        Log.i(TAG, "鈴憋笍 [Perf] Detection Latency: $detectionTime ms")
            // Close the dialog, then return to home.
        val actionStartTime = System.currentTimeMillis()
        
        // 馃殌 [Fix] Different exit strategies based on context
        val isLauncherContext = VIVO_LAUNCHER_PACKAGES.contains(pkg) || 
                                listOf("launcher", "home").any { pkg.contains(it, ignoreCase = true) }

        if (isLauncherContext &&
            lastLauncherHandleSignature == signature &&
            now - lastLauncherHandleAt < LAUNCHER_REPEAT_SUPPRESS_MS) {
            LogExporter.logEvent(TAG, "[HANDLE] Suppressed duplicate launcher trigger (${now - lastLauncherHandleAt}ms)")
            return
        }
        if (isLauncherContext) {
            lastLauncherHandleAt = now
            lastLauncherHandleSignature = signature
        }
        
        if (isAccessibilityContext) {
            Log.i(TAG, "[Exit Strategy] Accessibility Context: Triggering Double-Back to exit Settings")
            LogExporter.logEvent(TAG, "[ACTION] Exit strategy: Double-Back (Accessibility Context)")
            // 1. First Back - Dismiss Dialog
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // 2. Second Back - Exit Accessibility Settings Page
            Handler(Looper.getMainLooper()).postDelayed({
                val secondBack = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.d(TAG, "[Exit Strategy] Second Back Performed: $secondBack")
                // 3. Home - Go to Desktop
                Handler(Looper.getMainLooper()).postDelayed({
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }, ACCESSIBILITY_HOME_DELAY_MS)
            }, ACCESSIBILITY_SECOND_BACK_DELAY_MS)
        } else if (isLauncherContext) {
            // 馃殌 [Fix] On launcher: just press BACK to dismiss popup menu, then HOME
            // Do NOT do aggressive navigation that might accidentally enter settings
            Log.i(TAG, "[Exit Strategy] Launcher Context: BACK to dismiss popup, then HOME")
            LogExporter.logEvent(TAG, "[ACTION] Exit strategy: BACK+HOME (Launcher Context)")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Handler(Looper.getMainLooper()).postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }, LAUNCHER_HOME_DELAY_MS)
        } else {
            // Settings/Store context: BACK to dismiss dialog, then HOME
            LogExporter.logEvent(TAG, "[ACTION] Exit strategy: BACK+HOME (Settings/Store Context)")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        
        val actionEndTime = System.currentTimeMillis()
        
        Log.i(TAG, "鈴憋笍 [Perf] Global Actions Time: ${actionEndTime - actionStartTime} ms")
        
        hasPendingDialog = false
        isDialogShowing = false
        LogExporter.logEvent(TAG, "[Dialog] Disabled: anti-uninstall warning dialog skipped")
    }

    private fun showWarningDialog(context: Context, triggerTime: Long) {
        // 馃毇 [Self-Protection] 绔嬪嵆璁剧疆 isDialogShowing = true锛岄槻姝㈡椂闂寸獥鍙ｈ鍒╃敤
        // 杩欐牱鍦ㄤ换浣?early return 涔嬪墠锛宑heck() 鍑芥暟閮戒細璺宠繃妫€娴?        // 濡傛灉鏈€缁堜笉鏄剧ず寮圭獥锛屽啀璁惧洖 false
        val wasDialogShowing = isDialogShowing
        isDialogShowing = true  // 鍏堝崰浣嶏紒
        hasPendingDialog = false  // 宸茬粡杩涘叆澶勭悊娴佺▼
        
        if (wasDialogShowing) {
            // 涔嬪墠灏卞湪鏄剧ず锛屼笉闇€瑕佸啀寮?            Log.d(TAG, "[Dialog] Skip: wasDialogShowing=true")
            Log.d(TAG, "[Dialog] Skip: wasDialogShowing=true")
            LogExporter.logEvent(TAG, "[Dialog] Skip: already showing")
            return
        }
        
        val now = System.currentTimeMillis()
        if (now - lastDialogShowTime < MIN_DIALOG_INTERVAL_MS) {
            val remaining = MIN_DIALOG_INTERVAL_MS - (now - lastDialogShowTime)
            Log.d(TAG, "[Dialog] Skip: dialog cooldown, ${remaining}ms remaining")
            LogExporter.logEvent(TAG, "[Dialog] Skip: dialog cooldown (${remaining}ms left)")
            isDialogShowing = false  // 鎭㈠
            return
        }
        if (now - lastDialogDismissTime < POST_DISMISS_DIALOG_COOLDOWN_MS) {
            val remaining = POST_DISMISS_DIALOG_COOLDOWN_MS - (now - lastDialogDismissTime)
            Log.d(TAG, "[Dialog] Skip: post-dismiss cooldown, ${remaining}ms remaining")
            LogExporter.logEvent(TAG, "[Dialog] Skip: post-dismiss (${remaining}ms left)")
            isDialogShowing = false  // 鎭㈠
            return  // dismiss 鍚?3 绉掑唴涓嶅啀寮?
        }

        try {
            val startTime = System.currentTimeMillis()
            val currentLocale = context.resources.configuration.locales[0].language
            
            // 馃殌 [Performance Fix] 浼樺厛浣্চ中的棰勭儹鐨勭紦瀛樺璇濇
            // 濡傛灯缂撳瓨瀛樺湪涓旇瑷€鍖归厤锛岀洿鎺ユ樉绀猴紱鍚﹀垯鎵嶉噸鏂板垱寤?
            val dialogToShow: android.app.AlertDialog

            if (cachedDialog != null && cachedDialogLocale == currentLocale) {
                Log.d(TAG, "[Dialog] Using cached dialog for locale: $currentLocale")
                dialogToShow = cachedDialog!!
            } else {
                Log.d(TAG, "[Dialog] Creating new dialog for locale: $currentLocale (cached=$cachedDialogLocale)")
                val builder = android.app.AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.anti_uninstall_title))
                    .setMessage(context.getString(R.string.anti_uninstall_message))
                    .setPositiveButton(context.getString(R.string.btn_got_it), null)
                    .setNegativeButton(context.getString(R.string.btn_back), null)
                    .setOnDismissListener { isDialogShowing = false }
                
                dialogToShow = builder.create()
                // 馃殌 [Fix] Must set type to OVERLAY for Service context
                dialogToShow.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                
                // 鏇存柊缂撳瓨
                cachedDialog = dialogToShow
                cachedDialogLocale = currentLocale
            }
            
            // 纭繚 Dismiss Listener 鍦ㄦ瘡娆℃樉绀烘椂閮芥纭缃?
            dialogToShow.setOnDismissListener {
                isDialogShowing = false
                lastDialogDismissTime = System.currentTimeMillis()
                Log.d(TAG, "[Dialog] Dismissed, isDialogShowing=false")
                LogExporter.logEvent(TAG, "[Dialog] Dismissed")
            }
            dialogToShow.show()
            // isDialogShowing 宸茬粡鍦ㄥ嚱鏁板紑澶磋涓?true锛屾棤闇€鍐嶆璁剧疆
            lastDialogShowTime = System.currentTimeMillis()
            Log.d(TAG, "[Dialog] Successfully shown, isDialogShowing=true")
            LogExporter.logEvent(TAG, "[Dialog] Shown successfully")

            // 馃殌 [Secret] 5-tap on Message to export logs
            try {
                // Try to find the message text view by system ID
                val messageView = dialogToShow.findViewById<android.widget.TextView>(android.R.id.message)
                if (messageView != null) {
                    // Start fresh counters for this session
                    var tapCount = 0
                    var lastTapTime = 0L
                    
                    messageView.setOnClickListener {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime > 2500L) tapCount = 0
                        lastTapTime = now
                        tapCount++
                        
                        if (tapCount >= 5) {
                            tapCount = 0
                            // Show Selection Dialog
                            android.app.AlertDialog.Builder(context)
                                .setTitle("瀵煎嚭鏃ュ織 (闃插嵏杞?")
                                .setItems(com.ironwill.app.LogExporter.LOG_OPTIONS) { _, which ->
                                    val duration = com.ironwill.app.LogExporter.LOG_DURATIONS[which]
                                    val label = com.ironwill.app.LogExporter.LOG_OPTIONS[which]
                                    com.ironwill.app.LogExporter.exportAndShareRecent(context, duration, com.ironwill.app.LogExporter.DEFAULT_RECIPIENT, "IronWill 闃插嵏杞芥棩蹇?($label)")
                                }
                                .show()
                        }
                    }
                }
            } catch (ignored: Exception) {
                Log.w(TAG, "Failed to export/share log from dialog", ignored)
            }
            val endTime = System.currentTimeMillis()
            Log.i(TAG, "鈴憋笍 [Perf] Dialog Show Time: ${endTime - startTime} ms (Delay: ${startTime - triggerTime} ms)")
            
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to show Warning Dialog (BadToken/Permission)", e)
            isDialogShowing = false
            // 馃敡 娓呴櫎鍙兘鎹熷潖鐨勭紦瀛橈紝涓嬫寮哄埗閲嶆柊鍒涘缓
            cachedDialog = null
            cachedDialogLocale = null
            // Suppress crash to keep Service alive so Back/Home actions still work!
        }
    }
    
    /**
     * 馃殌 [Warmup] 棰勫垱寤哄璇濇锛屼緵澶栭儴璋冪敤
     */
    private fun logBlock(
        reason: String,
        pkg: String,
        cls: String?,
        context: String,
        sample: String,
        extras: String? = null
    ) {
        val safeSample = normalizeSample(sample, 120)
        val safeClass = cls ?: "n/a"
        val extraPart = if (extras.isNullOrBlank()) "" else " extras=$extras"
        LogExporter.logEvent(
            TAG,
            "[BLOCK] reason=$reason pkg=$pkg class=$safeClass context=$context sample=\"$safeSample\"$extraPart"
        )
    }

    private fun normalizeSample(text: String, max: Int): String {
        return text.replace(Regex("\\s+"), " ").trim().take(max)
    }

    fun warmupDialog(context: Context) {
    }

    /**
     * 馃殌 [Config Change] Reset cache with debounce and delayed warmup.
     * 馃敟 Keep main thread free for FastLockService first paint during locale switch.
     */
    fun resetDialogCache(context: Context) {
        hasPendingDialog = false
        isDialogShowing = false
        cachedDialog = null
        cachedDialogLocale = null
        pendingWarmupTask?.let { mainHandler.removeCallbacks(it) }
        pendingWarmupTask = null
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0, maxDepth: Int = 10, maxChildren: Int = 30) {
        if (node == null) return
        if (node.isEditable || node.className == "android.widget.EditText") return
        
        // 馃殌 [Performance] Safety Limits - balanced for detection speed
        if (depth > maxDepth) return
        if (sb.length > 5000) return
        
        if (!node.text.isNullOrEmpty()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrEmpty()) sb.append(node.contentDescription).append(" ")
        
        val count = node.childCount
        // 馃殌 [Performance] Limit children - use parameter
        // Increase limit for root to scan wide menus
        val effectiveLimit = if (depth == 0) maxChildren else 15
        val effectiveCount = kotlin.math.min(count, effectiveLimit)
        
        for (i in 0 until effectiveCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, sb, depth + 1, maxDepth, maxChildren)
                child.recycle()
                if (sb.length > 5000) break
            }
        }
    }

    private fun collectPrivateSpacePageHints(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int = 0,
        maxDepth: Int = 2,
        maxChildren: Int = 18
    ) {
        if (node == null) return
        if (depth > maxDepth) return
        if (sb.length > 1800) return

        if (!node.viewIdResourceName.isNullOrBlank()) sb.append(node.viewIdResourceName).append(" ")
        if (!node.className.isNullOrBlank()) sb.append(node.className).append(" ")
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrBlank()) sb.append(node.contentDescription).append(" ")

        val effectiveCount = kotlin.math.min(node.childCount, maxChildren)
        for (i in 0 until effectiveCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectPrivateSpacePageHints(child, sb, depth + 1, maxDepth, maxChildren)
                child.recycle()
                if (sb.length > 1800) break
            }
        }
    }

    // 馃殌 [Fix] Robust App Name Detection
    // Ignores "Clickable" occurrences (likely Desktop Icons)
    // Only flags "Static" occurrences (Likely Dialog Messages)
    private fun hasStaticAppNameMention(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        
        // Check current node
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        val content = text + " " + desc
        
        if (PROTECTED_APP_NAMES.any { content.contains(it, ignoreCase = true) }) {
             // Found match, verify if it is Static (Not Clickable)
             if (isNodeStatic(root)) {
                 return true
             }
        }

        // Check children
        val count = root.childCount
        // 馃殌 [Performance] Limit children check here too
        val effectiveCount = kotlin.math.min(count, 20)
        
        for (i in 0 until effectiveCount) {
            val child = root.getChild(i)
            if (child != null) {
                if (hasStaticAppNameMention(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun isNodeStatic(node: AccessibilityNodeInfo): Boolean {
        // If node itself is clickable, it's likely an Icon or Button -> Safe
        if (node.isClickable) return false
        
        // If Parent is clickable, it's likely a Compound Icon -> Safe
        val parent = node.parent
        if (parent != null) {
            if (parent.isClickable) return false
            // Check Grandparent (some launchers wrap twice)
            val grandParent = parent.parent
            if (grandParent != null && grandParent.isClickable) return false
        }
        
        // If here, Node and Parent(s) are NOT clickable.
        // It is likely a Static TextView in a Message Dialog.
        return true
    }

    private fun isMonitoredPackage(pkg: String): Boolean {
        return MONITORED_PACKAGES.any { pkg.startsWith(it) }
    }

    private fun shouldLogEvent(pkg: String, now: Long): Boolean {
        if (!isMonitoredPackage(pkg)) return false
        if (pkg == lastEventLogPackage && now - lastEventLogTime < EVENT_LOG_THROTTLE_MS) return false
        lastEventLogPackage = pkg
        lastEventLogTime = now
        return true
    }
}
