/**
 * =================================================================================
 * [Context Header]
 * * @file: LegalEntryRegistryTest.kt
 * * @role: Test
 * * @description: LegalDocumentRepository 7 个法务入口的契约测试。
 * * @responsibilities:
 * 1. 锁死 ABOUT_ENTRIES 的数量 = 7，顺序 = 入参顺序。
 * 2. 锁死每个入口的 id、title 与 raw 资源 id（防止后续误改）。
 * 3. 锁死首屏同意使用的《用户协议》《隐私政策》指向同一 raw 资源。
 * * @constraints (必读):
 * 1. 不依赖 Android Context / Resources，纯 JVM 测试。
 * 2. rawRes 是编译期常量，由 R.raw.* 在 JVM 单测下也可用（androidTest 不依赖）。
 * =================================================================================
 */
package com.decikeeper.app

import com.decikeeper.app.data.LegalDocumentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test



class LegalEntryRegistryTest {

    @Test
    fun aboutEntriesCountIsSeven() {
        assertEquals(
            "关于我们 入口数必须等于 7 (用户协议/隐私政策/第三方SDK/已收集个人信息/共享/账号注销/联系投诉)",
            7,
            LegalDocumentRepository.ABOUT_ENTRIES.size
        )
    }

    @Test
    fun aboutEntriesIdsAreUnique() {
        val ids = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }
        assertEquals(
            "id 列表去重后应与原列表等长",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun aboutEntriesOrderIsLocked() {
        // 锁死顺序：与"ACC-006 + REQ-5b"7 入口顺序一致
        val expected = listOf(
            "about_user_agreement",
            "about_privacy_policy",
            "about_third_party_sdk_list",
            "about_personal_info_list",
            "about_sharing_list",
            "about_account_deletion",
            "about_contact_and_complaint"
        )
        val actual = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun aboutEntriesTitlesAreLocked() {
        val expected = listOf(
            "用户协议",
            "隐私政策",
            "第三方 SDK 清单",
            "已收集个人信息清单",
            "与第三方共享信息清单",
            "账号注销入口",
            "联系和投诉方式"
        )
        val actual = LegalDocumentRepository.ABOUT_ENTRIES.map { it.title }
        assertEquals(expected, actual)
    }

    @Test
    fun aboutEntriesRawResAreAllDistinctAndNonZero() {
        val rawResIds = LegalDocumentRepository.ABOUT_ENTRIES.map { it.rawRes }
        // rawRes 来自 R.raw.*，编译期常量必定 > 0
        assertTrue("所有 raw 资源 id 必须 > 0", rawResIds.all { it != 0 })
        assertEquals(
            "raw 资源去重后应等于 7 (无重复)",
            7,
            rawResIds.toSet().size
        )
    }

    @Test
    fun aboutPrivacyPolicyRawMatchesConsentPageRaw() {
        val consent = LegalDocumentRepository.PRIVACY_POLICY
        val about = LegalDocumentRepository.ABOUT_ENTRIES
            .first { it.id == "about_privacy_policy" }
        assertEquals(
            "首屏同意页的《隐私政策》与「关于我们」的《隐私政策》必须指向同一 raw 资源",
            consent.rawRes,
            about.rawRes
        )
    }

    @Test
    fun aboutUserAgreementRawMatchesConsentPageRaw() {
        val consent = LegalDocumentRepository.USER_AGREEMENT
        val about = LegalDocumentRepository.ABOUT_ENTRIES
            .first { it.id == "about_user_agreement" }
        assertEquals(
            "首屏同意页的《用户协议》与「关于我们」的《用户协议》必须指向同一 raw 资源",
            consent.rawRes,
            about.rawRes
        )
    }

    @Test
    fun findByIdReturnsConsentDocs() {
        assertEquals(
            LegalDocumentRepository.PRIVACY_POLICY,
            LegalDocumentRepository.findById("privacy_policy")
        )
        assertEquals(
            LegalDocumentRepository.USER_AGREEMENT,
            LegalDocumentRepository.findById("user_agreement")
        )
    }

    @Test
    fun findByIdReturnsAboutDocs() {
        for (doc in LegalDocumentRepository.ABOUT_ENTRIES) {
            assertEquals(doc, LegalDocumentRepository.findById(doc.id))
        }
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(LegalDocumentRepository.findById(null))
        assertNull(LegalDocumentRepository.findById(""))
        assertNull(LegalDocumentRepository.findById("not_existing_doc"))
    }

    @Test
    fun versionStringsAreNonEmpty() {
        // REQ-5 Round 4 Fix #3: 版本号现在固定为 [待法务填写 vX.Y.Z] 占位符，
        // 不再硬编码具体版本。契约收缩为"非空 + 是占位符"。
        assertTrue(
            "PRIVACY_POLICY_VERSION 必须非空",
            LegalDocumentRepository.PRIVACY_POLICY_VERSION.isNotEmpty()
        )
        assertTrue(
            "USER_AGREEMENT_VERSION 必须非空",
            LegalDocumentRepository.USER_AGREEMENT_VERSION.isNotEmpty()
        )
    }

    @Test
    fun versionStringsUsePendingLegalReviewPlaceholder() {
        // REQ-5 Round 4 Fix #3: 法务未审核前,两个版本号必须等于
        // LegalDocumentRepository.PENDING_LEGAL_REVIEW 这个统一占位符,
        // 防止后续有人把某一个版本号误填成正式值而另一个仍是占位,
        // 造成 UI 显示"两版不一致"的产品事故。
        val placeholder = LegalDocumentRepository.PENDING_LEGAL_REVIEW
        assertTrue(
            "PENDING_LEGAL_REVIEW 占位符必须非空、且带 [ ... ] 占位边框(便于 grep)",
            placeholder.isNotEmpty() && placeholder.startsWith("[") && placeholder.endsWith("]")
        )
        assertEquals(
            "PRIVACY_POLICY_VERSION 必须引用 PENDING_LEGAL_REVIEW 占位符",
            placeholder,
            LegalDocumentRepository.PRIVACY_POLICY_VERSION
        )
        assertEquals(
            "USER_AGREEMENT_VERSION 必须引用 PENDING_LEGAL_REVIEW 占位符",
            placeholder,
            LegalDocumentRepository.USER_AGREEMENT_VERSION
        )
    }

    @Test
    fun pendingLegalReviewPlaceholderIsKeywordGreppableForLegal() {
        // 给法务 / 用户一把钥匙:可以全仓 grep "[待法务填写 vX.Y.Z]" 来发现
        // 还有哪些位置没被替换成正式版本号。
        val placeholder = LegalDocumentRepository.PENDING_LEGAL_REVIEW
        assertTrue(
            "PENDING_LEGAL_REVIEW 占位符必须含 'vX.Y.Z' 字面量,以便法务扫仓 grep",
            placeholder.contains("vX.Y.Z")
        )
        assertTrue(
            "PENDING_LEGAL_REVIEW 占位符必须含 '待法务填写',便于人阅读时一眼识别",
            placeholder.contains("待法务填写")
        )
    }

    @Test
    fun consentDocsIdsDoNotCollideWithAboutDocs() {
        val consentIds = setOf(
            LegalDocumentRepository.PRIVACY_POLICY.id,
            LegalDocumentRepository.USER_AGREEMENT.id
        )
        val aboutIds = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }.toSet()
        // 关于我们 的 7 个 id 集合不应与同意页 id 冲突（首屏用简写，关于用前缀）
        assertNotEquals(consentIds, aboutIds)
        // 任何 about entry id 都不应与 consent 简写 id 重复
        for (id in aboutIds) {
            assertTrue(
                "about entry id '$id' 不应与 consent doc id 重复",
                id !in consentIds
            )
        }
    }

    @Test
    fun consentAndAboutPrivacyPolicyPointToSameContent() {
        val consent = LegalDocumentRepository.PRIVACY_POLICY
        val about = LegalDocumentRepository.ABOUT_ENTRIES
            .first { it.id == "about_privacy_policy" }
        assertNotNull(consent)
        assertEquals(consent.id, "privacy_policy")
        assertEquals(about.id, "about_privacy_policy")
        // raw 资源一致（单测 7 已经测过，这里再冗余一次作 sanity check）
        assertEquals(consent.rawRes, about.rawRes)
    }
}
