/**
 * =================================================================================
 * [Context Header]
 * * @file: LegalDocumentRepository.kt
 * * @role: Domain Skill
 * * @description: 法务文档注册表与内容提供。
 * * @responsibilities:
 * 1. 集中管理"设置→关于我们"下 7 个法务入口与对应 raw 资源。
 * 2. 集中管理首屏同意弹窗所需的《用户协议》《隐私政策》内容。
 * 3. 提供隐私政策 client-side 版本号（与 PrivacyConsentManager 写入的版本号对齐）。
 * * @context:
 * - 上游: AboutActivity, LegalDocumentActivity, PrivacyConsentActivity
 * * @constraints (必读):
 * 1. 排序与展示名是合规契约，不能乱序。
 * 2. raw 资源 ID 在编译期固定，不允许运行时动态改。
 * 3. 新增法务入口必须同步更新：仓库 + raw 资源 + 7 入口测试。
 * =================================================================================
 */
package com.zhizhi.app.data

import androidx.annotation.RawRes
import com.zhizhi.app.R


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
 * 7 个"关于我们"入口 + 2 个"首屏同意"专用入口。
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

    /**
     * 客户端侧隐私政策版本号。
     *
     * REQ-5 Round 4 Fix #3: 此前硬编码 `v0.1.0-2026-07-05` 与 PLAN §7 "版本号不能
     * 凭空编造"约束冲突。改为占位符 [PENDING_LEGAL_REVIEW]，由法务 / 用户 review
     * 后再用 build-time 注入或人事 bump 写成正式版本。发布前**必须**把本常量替换为
     * 法务确认的实际版本号。
     */
    const val PRIVACY_POLICY_VERSION: String = PENDING_LEGAL_REVIEW

    /**
     * 客户端侧用户协议版本号。
     *
     * REQ-5 Round 4 Fix #3: 同 [PRIVACY_POLICY_VERSION]，未审核前固定占位。
     */
    const val USER_AGREEMENT_VERSION: String = PENDING_LEGAL_REVIEW

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

    // ── "关于我们" 7 个长期入口（顺序固定，详见单测） ────────────────
    val ABOUT_ENTRIES: List<LegalDocument> = listOf(
        LegalDocument(
            id = "about_user_agreement",
            title = "用户协议",
            rawRes = R.raw.user_agreement
        ),
        LegalDocument(
            id = "about_privacy_policy",
            title = "隐私政策",
            rawRes = R.raw.privacy_policy
        ),
        LegalDocument(
            id = "about_third_party_sdk_list",
            title = "第三方 SDK 清单",
            rawRes = R.raw.third_party_sdk_list
        ),
        LegalDocument(
            id = "about_personal_info_list",
            title = "已收集个人信息清单",
            rawRes = R.raw.personal_info_list
        ),
        LegalDocument(
            id = "about_sharing_list",
            title = "与第三方共享信息清单",
            rawRes = R.raw.sharing_list
        ),
        LegalDocument(
            id = "about_account_deletion",
            title = "账号注销入口",
            rawRes = R.raw.account_deletion
        ),
        LegalDocument(
            id = "about_contact_and_complaint",
            title = "联系和投诉方式",
            rawRes = R.raw.contact_and_complaint
        )
    )

    /** 通过 id 查找文档；找不到返回 null。 */
    fun findById(id: String?): LegalDocument? {
        if (id == null) return null
        if (id == USER_AGREEMENT.id) return USER_AGREEMENT
        if (id == PRIVACY_POLICY.id) return PRIVACY_POLICY
        return ABOUT_ENTRIES.firstOrNull { it.id == id }
    }
}
