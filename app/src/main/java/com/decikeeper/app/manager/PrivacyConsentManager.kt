/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacyConsentManager.kt
 * * @role: Domain Skill
 * * @description: 用户协议与隐私政策的同意状态管理（SharedPreferences 持久化）。
 * * @responsibilities:
 * 1. 维护"是否已同意"的本地状态。
 * 2. 提供同步读取入口（用于 Application 启动期决定是否放行 post-consent 初始化）。
 * 3. 默认"未同意"——失败回退为未同意，绝不悄悄放行。
 * * @context:
 * - 上游: PrivacyConsentActivity, MyApplication, AboutActivity
 * * @constraints (必读):
 * 1. 该状态是合规闸门。读取失败必须回退到"未同意"。
 * 2. 不在备份/换机迁移中保留（由 backup_rules.xml / data_extraction_rules.xml 显式 exclude）。
 * 3. 仅写"已同意 / 未同意"二值；不存版本号，版本号属于"政策版本"字段，参见 LegalDocumentRepository。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 用户协议 + 隐私政策的本地同意状态层。
 *
 * 写入路径：用户在 PrivacyConsentActivity 中点击「同意」。
 * 读取路径：MyApplication.onCreate 同步读 → 决定是否跑 post-consent 组件。
 *           PrivacyConsentActivity.onCreate 同步读 → 决定是否跳过同意页直接进主页。
 *
 * 注意：未在隐私政策正文里提及的 SharedPreferences key 必须以"consent_"开头，
 * 便于 backup_rules.xml / data_extraction_rules.xml 用通配 exclude。
 */
object PrivacyConsentManager {

    /** 独立 prefs 文件，避免和其他业务配置耦合，方便备份排除。 */
    const val PREFS_NAME: String = "privacy_consent_prefs"

    /** key：是否已同意协议+隐私 */
    const val KEY_CONSENTED: String = "consent_accepted"

    /** key：用户首次同意时的 Unix 毫秒时间（用于审计与版本追溯） */
    const val KEY_CONSENTED_AT_MS: String = "consent_accepted_at_ms"

    /** key：用户同意时所看到的隐私政策版本号（client-side version） */
    const val KEY_CONSENTED_POLICY_VERSION: String = "consent_accepted_policy_version"

    private const val TAG = "PrivacyConsent"

    /**
     * 同步读取是否已同意。
     *
     * - 任意异常 / 缺失值 → false（未同意）。
     * - 不抛异常给上层，避免拖垮 Application.onCreate。
     */
    fun isConsented(context: Context): Boolean {
        return try {
            val prefs = prefs(context)
            prefs.getBoolean(KEY_CONSENTED, false)
        } catch (e: Exception) {
            Log.w(TAG, "isConsented: read failed, fallback to false", e)
            false
        }
    }

    /**
     * 写入"已同意"，并记录同意时间与当时所见的政策版本。
     *
     * 使用 commit() 而非 apply()，确保磁盘落盘后再返回，避免以下竞态：
     *  - 应用同意后立即被杀 → 状态文件未写盘 → 下次冷启动仍弹同意页（用户体验差）
     *  - MyApplication.onCreate / PrivacyConsentActivity 立即跳主页 → 主页逻辑读到"未同意"
     *
     * @return true = commit 成功落盘；false = commit 失败（理论上几乎不会失败，
     *         但磁盘满 / 文件锁 / SELinux 拒绝等极端情况可能发生）。失败时调用方
     *         **必须**阻止用户进入主页（合规闸门不能形同虚设）。
     */
    fun markConsented(context: Context, policyVersion: String): Boolean {
        return try {
            val ok = prefs(context).edit()
                .putBoolean(KEY_CONSENTED, true)
                .putLong(KEY_CONSENTED_AT_MS, System.currentTimeMillis())
                .putString(KEY_CONSENTED_POLICY_VERSION, policyVersion)
                .commit()
            if (ok) {
                Log.i(TAG, "markConsented: committed OK, policyVersion=$policyVersion")
            } else {
                Log.w(TAG, "markConsented: commit() returned false (disk full or locked?)")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "markConsented: write failed", e)
            false
        }
    }

    /**
     * 仅供测试 / 调试使用：清除同意状态，强制重新走同意流程。
     * 业务路径不应调用。
     */
    fun resetForDebug(context: Context) {
        try {
            prefs(context).edit()
                .remove(KEY_CONSENTED)
                .remove(KEY_CONSENTED_AT_MS)
                .remove(KEY_CONSENTED_POLICY_VERSION)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "resetForDebug: failed", e)
        }
    }

    /** 仅供测试 / 审计使用：读取同意时间戳。 */
    fun getConsentedAtMs(context: Context): Long {
        return try {
            prefs(context).getLong(KEY_CONSENTED_AT_MS, 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 纯函数决策：给定同意状态，是否应执行 post-consent 组件初始化。
     *
     * 抽出该函数以让单测在不需要 Android Context 的情况下覆盖核心 gate 行为。
     * 调用方：MyApplication.initializePostConsentComponentsIfNeeded()。
     *
     * 规则：
     *  - 未同意 → false（跳过 post-consent 组件）。
     *  - 已同意 → true。
     *  - 读 prefs 异常 → false（不静默放行）。
     */
    fun shouldInitializePostConsentComponents(isConsented: Boolean): Boolean {
        return isConsented
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
