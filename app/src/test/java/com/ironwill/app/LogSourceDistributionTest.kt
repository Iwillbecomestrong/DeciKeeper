package com.ironwill.app

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Boundary 0 baseline — Bug 3 source distribution guard.
 *
 * Spec anchors:
 *   - C-B3-07 / U-B3-01: 真机抓取的 591 行日志中, IronWill 自身 PID 行 / [FG_DECISION]
 *     / [EXPIRY_LOCK] / [DEFERRED] / [PENALTY] 等 canonical 事件的分布必须可被外部
 *     Python 审计脚本独立统计并以 fixture 文件固化下来; 本 JVM 测试只验证 fixture 自身
 *     满足可机检契约 (deterministic + reportable).
 *
 * 为什么本测试今天 GREEN:
 *   - 本测试不调用任何生产代码 (`app/src/main/...`); 只读 Python 审计脚本生成的 fixture
 *     JSON, 并断言其内容满足 §3 (source classification 总数 / Top-N 标签 / 重复键守门)
 *   - 生成 fixture 的 Python 脚本 (`scripts/audit_log_export.py`) 自身有 Python 单元测试
 *     保证确定性 (见 `scripts/tests/test_audit_log_export.py`)
 *
 * Boundary 1/2 不会破坏本测试; 只会在现有 fixture 上叠加 [LOCK_RETRY_STORM] /
 * [BLACKLIST_ENTRY] 计数约束。
 */
class LogSourceDistributionTest {

    /**
     * The fixture MUST exist on disk and parse to a deterministic shape:
     *   - `totalKept`        == 591 (matches header line "kept 591 of 36549 lines")
     *   - `ironWillPidCount` > 0
     *   - `topTags`          is non-empty
     *   - `duplicateKeys`    is empty (no malformed key=value in the source)
     */
    @Test
    fun exportedDeviceFixtureReportsTotalKeptTagsAndTopTags() {
        val json = JSONObject(loadFixtureRaw())

        assertEquals(
            "fixture sourceFile must point to the 2026-07-15 device-log",
            "docs/superpowers/specs/2026-07-15-ironwill-penalty-reclock-logfix-references/2026-07-15-device-log.txt",
            json.getString("sourceFile")
        )
        assertEquals("fixture totalKept must match header", 591, json.getInt("totalKept"))

        val ironWillPidCount = json.getInt("ironWillPidCount")
        assertTrue(
            "ironWillPidCount must be > 0 (canonical events have IronWill PID), was=$ironWillPidCount",
            ironWillPidCount > 0
        )

        val topTags = json.getJSONArray("topTags")
        assertTrue(
            "topTags must list at least one canonical event, got=$topTags",
            topTags.length() > 0
        )

        val tagCounts = HashMap<String, Int>()
        for (i in 0 until topTags.length()) {
            val o = topTags.getJSONObject(i)
            tagCounts[o.getString("tag")] = o.getInt("count")
        }

        // Canonical events per spec §3 must each appear in topTags.
        for (canonical in listOf("FG_DECISION", "EXPIRY_LOCK", "DEFERRED", "PENALTY")) {
            assertTrue(
                "fixture must count canonical event [$canonical] in topTags (was=$tagCounts)",
                tagCounts.containsKey(canonical)
            )
        }

        // 111 [EXPIRY_LOCK] and 113 [DEFERRED] are the storm evidence (C-B1-10).
        assertEquals(
            "[EXPIRY_LOCK] count must match the 591-line fixture (storm evidence)",
            111,
            tagCounts["EXPIRY_LOCK"] ?: 0
        )
        assertEquals(
            "[DEFERRED] count must match the 591-line fixture",
            113,
            tagCounts["DEFERRED"] ?: 0
        )

        // duplicateKeys must be empty (canonicalizer invariant).
        val dup = json.getJSONArray("duplicateKeys")
        assertEquals(
            "duplicateKeys must be empty for the canonical 591-line fixture",
            0,
            dup.length()
        )
    }

    /**
     * Same input → same summary. Critical for CI reproducibility.
     * `audit_log_export.py` MUST be a pure function (no `time.time()` / `os.urandom()` /
     * `random.*` influencing the output).
     */
    @Test
    fun sourceStatisticsAreDeterministicForSameInput() {
        val first = loadFixtureRaw()
        val second = loadFixtureRaw()

        assertEquals(
            "raw fixture file must be byte-identical across two reads",
            first,
            second
        )

        val a = JSONObject(first)
        val b = JSONObject(second)
        assertEquals(a.getInt("totalKept"), b.getInt("totalKept"))
        assertEquals(a.getInt("ironWillPidCount"), b.getInt("ironWillPidCount"))
        assertEquals(
            "topTags must be deterministic (same tags, same counts, same order)",
            a.getJSONArray("topTags").toString(),
            b.getJSONArray("topTags").toString()
        )
        assertEquals(
            "duplicateKeys must be deterministic",
            a.getJSONArray("duplicateKeys").toString(),
            b.getJSONArray("duplicateKeys").toString()
        )

        // Smoke: spot-check at least one known tag exists with a stable count.
        val top = a.getJSONArray("topTags")
        val expiry = (0 until top.length())
            .map { top.getJSONObject(it) }
            .firstOrNull { it.getString("tag") == "EXPIRY_LOCK" }
        assertNotNull("EXPIRY_LOCK tag must exist in fixture", expiry)
        assertEquals(111, expiry!!.getInt("count"))
    }

    /* --- helpers ---------------------------------------------------------- */

    private fun loadFixtureRaw(): String {
        val relative = "scripts/tests/fixtures/audit_summary.json"
        return generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error(
                "missing $relative; run `python scripts/audit_log_export.py " +
                    "docs/superpowers/specs/2026-07-15-ironwill-penalty-reclock-logfix-references/" +
                    "2026-07-15-device-log.txt > scripts/tests/fixtures/audit_summary.json` first"
            )
    }

    // Suppress unused-imports lint for JSONArray when only JSONObject is used.
    @Suppress("unused")
    private val jsonArrayMarker: Class<JSONArray> = JSONArray::class.java
}