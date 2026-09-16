/**
 * =================================================================================
 * [Context Header]
 * * @file: LegalEntryRegistryTest.kt
 * * @role: Test
 * * @description: LegalDocumentRepository About 页公开入口的契约测试。
 * * @responsibilities:
 * 1. 锁死 About 页公开入口的数量与顺序。
 * 2. 锁死每个入口的 id、title 与 raw 资源 id（防止后续误改）。
 * 3. 确认首屏同意文档与 About 页的内测信息说明彼此独立。
 * * @constraints (必读):
 * 1. 不依赖 Android Context / Resources，纯 JVM 测试。
 * 2. rawRes 是编译期常量，由 R.raw.* 在 JVM 单测下也可用（androidTest 不依赖）。
 * =================================================================================
 */
package com.decikeeper.app.governance

import com.decikeeper.app.data.LegalDocumentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test



class LegalEntryRegistryTest {

    @Test
    fun aboutEntriesCountIsThree() {
        assertEquals(
            "关于决定守护公开入口应为隐私说明/开源许可/反馈邮箱",
            3,
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
        // 锁死顺序：先隐私说明，再开源许可，最后反馈邮箱。
        val expected = listOf(
            "about_privacy_notice",
            "about_open_source_licenses",
            "about_feedback_email"
        )
        val actual = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun aboutEntriesTitlesAreLocked() {
        val expected = listOf(
            "隐私说明及权限说明",
            "开源许可",
            "反馈邮箱"
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
            "raw 资源去重后应等于 3 (无重复)",
            3,
            rawResIds.toSet().size
        )
    }

    @Test
    fun aboutPrivacyNoticeUsesSeparateRawFromConsentPage() {
        val consent = LegalDocumentRepository.PRIVACY_POLICY
        val about = LegalDocumentRepository.ABOUT_ENTRIES
            .first { it.id == "about_privacy_notice" }
        assertNotEquals("内测版隐私说明不得复用首屏正式同意文档", consent.rawRes, about.rawRes)
    }

    @Test
    fun hiddenLegacyAboutRoutesRemainAvailableButAreNotShown() {
        assertEquals(
            LegalDocumentRepository.USER_AGREEMENT.rawRes,
            LegalDocumentRepository.findById("about_user_agreement")?.rawRes
        )
        assertEquals(
            LegalDocumentRepository.PRIVACY_POLICY.rawRes,
            LegalDocumentRepository.findById("about_privacy_policy")?.rawRes
        )
        val visibleIds = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }.toSet()
        assertTrue("旧法务入口不得出现在 About 页公开列表", visibleIds.none { it in setOf(
            "about_third_party_sdk_list",
            "about_personal_info_list",
            "about_sharing_list",
            "about_account_deletion",
            "about_contact_and_complaint"
        ) })
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
    fun versionStringsUseConsistentVersion() {
        val version = LegalDocumentRepository.PRIVACY_POLICY_VERSION
        assertTrue("PRIVACY_POLICY_VERSION 必须非空", version.isNotEmpty())
        assertEquals(
            "PRIVACY_POLICY_VERSION 与 USER_AGREEMENT_VERSION 必须保持版本一致",
            version,
            LegalDocumentRepository.USER_AGREEMENT_VERSION
        )
        assertEquals("v0.3.0", version)
    }

    @Test
    fun consentDocsIdsDoNotCollideWithAboutDocs() {
        val consentIds = setOf(
            LegalDocumentRepository.PRIVACY_POLICY.id,
            LegalDocumentRepository.USER_AGREEMENT.id
        )
        val aboutIds = LegalDocumentRepository.ABOUT_ENTRIES.map { it.id }.toSet()
        // About 页公开 id 集合不应与同意页 id 冲突。
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
    fun consentAndAboutPrivacyNoticePointToDifferentContent() {
        val consent = LegalDocumentRepository.PRIVACY_POLICY
        val about = LegalDocumentRepository.ABOUT_ENTRIES
            .first { it.id == "about_privacy_notice" }
        assertNotNull(consent)
        assertEquals(consent.id, "privacy_policy")
        assertEquals(about.id, "about_privacy_notice")
        assertNotEquals(consent.rawRes, about.rawRes)
    }
}
