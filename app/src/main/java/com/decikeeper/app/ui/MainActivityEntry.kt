/**
 * =================================================================================
 * [Context Header]
 * * @file: MainActivityEntry.kt
 * * @role: Domain Skill
 * * @description: 唯一受信任的"安全启动 MainActivity"入口。
 * * @responsibilities:
 * 1. 集中校验隐私政策同意状态，未同意时阻止进入主页（合规闸门）。
 * 2. 把"同意前进入主页"的所有历史旁路收口到统一函数（BootReceiver/CoreService/
 *    MonitorService/A11yGuardService/AlarmGuardianService/PermissionManager）。
 * 3. 区分"普通跳转"和"系统级唤醒"两种调用语义：
 *    - 普通跳转：未同意 → 重定向到 PrivacyConsentActivity（让用户先看到协议）。
 *    - 系统级唤醒：未同意 → 直接拒绝启动任何 Activity（不在锁屏/未解锁时弹 UI）。
 * * @context:
 * - 上游: 所有历史代码中直接 startActivity(Intent(this, MainActivity::class.java))
 *         的调用点。
 * - 下游: PrivacyConsentManager, PrivacyConsentActivity, MainActivity。
 * * @constraints (必读):
 * 1. 严禁跳过 decideMainActivityEntry() 直接 startActivity(MainActivity::class.java)。
 * 2. 严禁把同意状态读不到当"放行"（读取失败必须回退为未同意）。
 * 3. 不在此处修改被调用者的 extras（调用方传 Extra 让入口函数透明转发）。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.decikeeper.app.manager.PrivacyConsentManager


/**
 * MainActivity 的"安全入口"决策与启动工具。
 *
 * 历史问题：项目内至少有 7 个外部入口（BootReceiver、CoreService、MonitorService、
 * A11yGuardService、AlarmGuardianService、PermissionManager×2）会在用户尚未同意
 * 协议时直接 startActivity(MainActivity)，绕过 PrivacyConsentActivity 的 gate。
 *
 * 本对象把所有这些旁路收口为两个函数 + 一个纯函数决策：
 *  - [decideMainActivityEntry] — 纯函数，单测可直接覆盖
 *  - [start] — 业务方调用；未同意 → 跳 PrivacyConsentActivity
 *  - [startFromSystem] — 系统级唤醒（如 BootReceiver）；未同意 → 静默拒绝
 */
object MainActivityEntry {

    private const val TAG = "MainActivityEntry"

    /**
     * 调用来源标记。普通用户可见跳转 vs 系统级唤醒。
     */
    enum class CallSource {
        /** 业务方（通知点击、应用内跳转等） */
        NORMAL,
        /** 系统级唤醒（开机广播、未解锁 Direct Boot 等） */
        SYSTEM
    }

    /**
     * 决策结果。纯函数，便于单测。
     */
    enum class EntryAction {
        /** 已同意 → 正常启动 MainActivity */
        PROCEED,
        /** 未同意 + 普通跳转 → 启动 PrivacyConsentActivity */
        REDIRECT_TO_CONSENT,
        /** 未同意 + 系统级唤醒 → 静默拒绝，不启动任何 UI */
        DENY
    }

    /**
     * 纯函数决策：给定同意状态 + 调用来源，决定下一步动作。
     *
     * 规则（按任务清单 §Fix #1）：
     *  - 已同意 → 无论来源都 [EntryAction.PROCEED]
     *  - 未同意 + [CallSource.NORMAL] → [EntryAction.REDIRECT_TO_CONSENT]
     *  - 未同意 + [CallSource.SYSTEM]  → [EntryAction.DENY]
     *    （系统级唤醒不应强行弹 UI，例如 LOCKED_BOOT_COMPLETED 时设备可能未解锁，
     *     此时弹同意页既不可见又会产生副作用。）
     */
    fun decideMainActivityEntry(
        isConsented: Boolean,
        callSource: CallSource
    ): EntryAction {
        if (isConsented) return EntryAction.PROCEED
        return when (callSource) {
            CallSource.NORMAL -> EntryAction.REDIRECT_TO_CONSENT
            CallSource.SYSTEM -> EntryAction.DENY
        }
    }

    /**
     * 业务方调用的统一入口。未同意 → 跳 PrivacyConsentActivity（让用户先看到协议）。
     *
     * 调用方传入已经准备好的 baseIntent（带 extras / flags），入口函数：
     *   1. 同步读同意状态
     *   2. 决策：PROCEED → startActivity(baseIntent) 指向 MainActivity
     *   3. REDIRECT → 改投到 PrivacyConsentActivity，flags 保留
     *   4. DENY → 理论不会出现在 NORMAL 路径，仅兜底
     *
     * @param context 任意 Context（Activity / Service / Receiver / Application）
     * @param baseIntent 已经填好 extras / flags 的 MainActivity Intent
     * @param callSource 调用来源；业务方传 [CallSource.NORMAL]
     */
    fun start(context: Context, baseIntent: Intent, callSource: CallSource = CallSource.NORMAL) {
        val consented = PrivacyConsentManager.isConsented(context)
        when (decideMainActivityEntry(consented, callSource)) {
            EntryAction.PROCEED -> {
                try {
                    context.startActivity(baseIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "start: failed to launch MainActivity", e)
                }
            }
            EntryAction.REDIRECT_TO_CONSENT -> {
                Log.w(TAG, "start: not consented, redirecting to PrivacyConsentActivity")
                redirectToConsent(context, baseIntent)
            }
            EntryAction.DENY -> {
                Log.w(TAG, "start: not consented and callSource=SYSTEM, silently denying")
            }
        }
    }

    /**
     * 系统级唤醒入口（如 BootReceiver 开机广播）。未同意 → 静默拒绝，不启动任何 UI。
     */
    fun startFromSystem(context: Context, baseIntent: Intent) {
        start(context, baseIntent, CallSource.SYSTEM)
    }

    /**
     * 直接构造一个 MainActivity Intent 并以指定 callSource 启动。
     * 给调用方最简单的接入路径。
     */
    fun start(context: Context, callSource: CallSource = CallSource.NORMAL) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, intent, callSource)
    }

    private fun redirectToConsent(context: Context, baseIntent: Intent) {
        // REQ-5 Round 3 Fix #2: 把 baseIntent 的 extras 序列化到独立 prefs，
        // 让 PrivacyConsentActivity 同意后能回放到 MainActivity。
        // （不能在 startActivity 之间透传 intent extras，否则同意页的"裸"启动
        // 会丢掉原始调用方语义，例如 MonitorService 的 show_permission_dialog。）
        PendingMainActivityExtras.save(context, baseIntent)

        val consentIntent = Intent(context, PrivacyConsentActivity::class.java).apply {
            // 保留 flags，但去掉 CLEAR_TOP（与新启动任务栈语义冲突）
            val flags = baseIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP.inv()
            addFlags(flags)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // 用 extra 通知 PrivacyConsentActivity 同意后有"待回放"的 baseIntent。
            putExtra(PrivacyConsentActivity.EXTRA_HAS_PENDING_EXTRAS, true)
        }
        try {
            context.startActivity(consentIntent)
        } catch (e: Exception) {
            Log.e(TAG, "redirectToConsent: failed to launch PrivacyConsentActivity", e)
        }
    }
}