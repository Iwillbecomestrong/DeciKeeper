/**
 * =================================================================================
 * [Context Header]
 * * @file: LegalDocumentRepository.kt
 * * @role: Domain Skill
 * * @description: 法务文档注册表与内容提供。
 * * @responsibilities:
 * 1. 集中管理"设置→关于决定守护"下当前内测版公开的信息入口与对应 raw 资源。
 * 2. 集中管理首屏同意弹窗所需的《用户协议》《隐私政策》内容。
 * 3. 提供隐私政策 client-side 版本号（与 PrivacyConsentManager 写入的版本号对齐）。
 * * @context:
 * - 上游: AboutActivity, LegalDocumentActivity, PrivacyConsentActivity
 * * @constraints (必读):
 * 1. 排序与展示名是 UI 契约，不能乱序。
 * 2. raw 资源 ID 在编译期固定，不允许运行时动态改。
 * 3. 首屏同意所需的用户协议 / 隐私政策与 About 页公开信息入口分开维护。
 * =================================================================================
 */
package com.decikeeper.app.data

import androidx.annotation.RawRes
import com.decikeeper.app.R


/**
 * 法务/合规文档元数据。
 *
 * @param id 路由 id，用于 Intent extra 与单测断言。
 * @param title 列表上显示的标题。
 * @param rawRes 文本内容（UTF-8 txt）。
 */
data class LegalDocument(
    val id: String,
    val title: String,
    @RawRes val rawRes: Int
)

/**
 * 法务/合规文档注册表。
 *
 * 当前 About 页 3 个文档入口 + 2 个"首屏同意"专用入口。
 * 顺序就是用户看到的顺序，**单测会锁死顺序**。
 */
object LegalDocumentRepository {

    /**
     * REQ-5 Round 4 Fix #3: 法务未审核前使用的版本占位串。
     *
     * PLAN §7 明确约束"开发者/运营主体名称、联系方式、投诉方式、版本号、生效日期
     * 不能凭空编造"，所以在法务 / 用户给出正式版本号之前，下游任何拼装"vX.Y.Z"
     * 字符串的位置都必须用此占位符。**绝不要**把它换成 `v0.x.y-2026-xx-xx` 之类
     * 的伪造版本 —— 上线前必须由法务确认。
     */
    const val PENDING_LEGAL_REVIEW: String = "[待法务填写 vX.Y.Z]"

    const val PRIVACY_POLICY_VERSION: String = "v0.4.0"

    /**
     * 客户端侧用户协议版本号。
     */
    const val USER_AGREEMENT_VERSION: String = "v0.4.0"

    // ── 首屏同意专用 ────────────────────────────────────────────────
    val USER_AGREEMENT: LegalDocument = LegalDocument(
        id = "user_agreement",
        title = "用户协议",
        rawRes = R.raw.user_agreement
    )

    val PRIVACY_POLICY: LegalDocument = LegalDocument(
        id = "privacy_policy",
        title = "隐私政策",
        rawRes = R.raw.privacy_policy
    )

    // ── About 页公开入口（顺序固定；旧合规入口保留但不展示） ────────
    val ABOUT_ENTRIES: List<LegalDocument> = listOf(
        LegalDocument(
            id = "about_privacy_notice",
            title = "隐私说明及权限说明",
            rawRes = R.raw.privacy_notice
        ),
        LegalDocument(
            id = "about_open_source_licenses",
            title = "开源许可",
            rawRes = R.raw.open_source_licenses
        ),
        LegalDocument(
            id = "about_feedback_email",
            title = "反馈邮箱",
            rawRes = R.raw.feedback_email
        )
    )

    /** 旧版 About 页入口仅保留路由兼容，不再加入 [ABOUT_ENTRIES]，避免误导内测用户。 */
    private val HIDDEN_LEGACY_ABOUT_ENTRIES: List<LegalDocument> = listOf(
        LegalDocument("about_user_agreement", "用户协议", R.raw.user_agreement),
        LegalDocument("about_privacy_policy", "隐私政策", R.raw.privacy_policy),
        LegalDocument("about_third_party_sdk_list", "第三方 SDK 清单", R.raw.third_party_sdk_list),
        LegalDocument("about_personal_info_list", "已收集个人信息清单", R.raw.personal_info_list),
        LegalDocument("about_sharing_list", "与第三方共享信息清单", R.raw.sharing_list),
        LegalDocument("about_account_deletion", "账号注销入口", R.raw.account_deletion),
        LegalDocument("about_contact_and_complaint", "联系和投诉方式", R.raw.contact_and_complaint)
    )

    /** 通过 id 查找文档；找不到返回 null。 */
    fun findById(id: String?): LegalDocument? {
        if (id == null) return null
        if (id == USER_AGREEMENT.id) return USER_AGREEMENT
        if (id == PRIVACY_POLICY.id) return PRIVACY_POLICY
        return (ABOUT_ENTRIES + HIDDEN_LEGACY_ABOUT_ENTRIES).firstOrNull { it.id == id }
    }
}
