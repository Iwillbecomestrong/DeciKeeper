package com.decikeeper.app.data

import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.TimeRuleManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test




/**
 * TASK-01 (segment-blacklist) RED — TimeSegment.listApps 数据模型 + JSON 兼容。
 *
 * 语义（SPEC §2 / §7）：
 *   - listApps == null  → 跟随全局黑名单（继承态）。
 *   - listApps != null 且非空 → 本时段独立名单（脱钩态）。
 *   - listApps == emptyList() → 本时段什么都不锁（脱钩后清空）。
 *
 * JSON 契约（SPEC §7）：
 *   - 序列化仅在 listApps != null 时写 listApps 键（缺省不写，减小体积）。
 *   - 反序列化：JSON 里无键 → null；JSON 里存在但为空数组 → 空列表
 *     （必须与 null 区分——脱钩后清空 vs 跟随全局）。
 *
 * segmentsToJson / parseSegmentsFromJson 是 TimeRuleManager object 的纯函数
 * （仅依赖 org.json，不触碰 Android Context / Log / SharedPreferences），因此本测试
 * 为纯 JVM 测试，无需 Robolectric。
 */
class SegmentListJsonCompatTest {

    private fun seg(
        name: String = "时段",
        listApps: List<String>? = null,
        daysOfWeek: List<Int>? = null
    ): TimeSegment = TimeSegment(
        startHour = 8, startMinute = 0,
        endHour = 22, endMinute = 0,
        limitMins = 60,
        name = name,
        daysOfWeek = daysOfWeek,
        listApps = listApps
    )

    // ────────────────────────────────────────────────────────────
    //  #1 老数据兼容：不含 listApps 的旧 JSON → listApps == null（跟随全局）
    // ────────────────────────────────────────────────────────────

    @Test
    fun parseOldJsonWithoutListApps_returnsNull() {
        // 用 org.json 构造一段不含 listApps 的旧数据（字段与生产 segmentsToJson 一致）。
        val oldObj = JSONObject()
        oldObj.put("startHour", 8)
        oldObj.put("startMinute", 0)
        oldObj.put("endHour", 22)
        oldObj.put("endMinute", 0)
        oldObj.put("limitMins", 60)
        oldObj.put("name", "深夜")
        oldObj.put("isCustomized", false)
        oldObj.put("isEnabled", true)
        val daysArr = JSONArray()
        for (d in 1..7) daysArr.put(d)
        oldObj.put("daysOfWeek", daysArr)

        val oldArray = JSONArray().put(oldObj)

        val parsed = TimeRuleManager.parseSegmentsFromJson(oldArray.toString())

        assertEquals("旧 JSON 应解析出 1 个段", 1, parsed.size)
        val segment = parsed[0]
        assertEquals("段名应正确解析", "深夜", segment.name)
        assertNull("老数据无 listApps 键 → listApps 应为 null（跟随全局黑名单）", segment.listApps)
    }

    // ────────────────────────────────────────────────────────────
    //  #2 往返 1：非空 listApps 正确恢复
    // ────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_nonEmptyListApps_restoredExactly() {
        val original = listOf(
            seg(name = "深夜", listApps = listOf("com.tencent.mobileqq", "com.tencent.mm"))
        )

        val json = TimeRuleManager.segmentsToJson(original)
        val restored = TimeRuleManager.parseSegmentsFromJson(json)

        assertEquals(1, restored.size)
        assertEquals(
            "非空 listApps 应往返恢复为 [com.tencent.mobileqq, com.tencent.mm]",
            listOf("com.tencent.mobileqq", "com.tencent.mm"),
            restored[0].listApps
        )
    }

    // ────────────────────────────────────────────────────────────
    //  #3 往返 2：null listApps → 序列化不含 listApps 键 → 解析回 null
    // ────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_nullListApps_omitsKeyAndParsesBackToNull() {
        val original = listOf(seg(name = "继承态", listApps = null))

        val json = TimeRuleManager.segmentsToJson(original)

        // 序列化后不含 listApps 键（缺省不写以减小体积）。
        val jsonObj = JSONArray(json).getJSONObject(0)
        assertEquals(
            "listApps == null 时序列化不应包含 listApps 键",
            false,
            jsonObj.has("listApps")
        )

        val restored = TimeRuleManager.parseSegmentsFromJson(json)
        assertEquals(1, restored.size)
        assertNull("null listApps 解析回 null（跟随全局）", restored[0].listApps)
    }

    // ────────────────────────────────────────────────────────────
    //  #4 区分性：emptyList() 与 null 区分
    // ────────────────────────────────────────────────────────────

    @Test
    fun emptyList_isDistinctFromNull_serializesEmptyArrayAndParsesBackToEmpty() {
        val original = listOf(seg(name = "脱钩清空", listApps = emptyList()))

        val json = TimeRuleManager.segmentsToJson(original)

        // 序列化后 listApps 键存在且为空数组（区别于 null 的缺省）。
        val jsonObj = JSONArray(json).getJSONObject(0)
        assertEquals("listApps == emptyList() 时必须写 listApps 键", true, jsonObj.has("listApps"))
        val listAppsArr = jsonObj.getJSONArray("listApps")
        assertEquals("空列表序列化为空数组", 0, listAppsArr.length())

        val restored = TimeRuleManager.parseSegmentsFromJson(json)
        assertEquals(1, restored.size)
        assertEquals(
            "空数组必须解析回空列表（脱钩后清空），而不是 null（跟随全局）",
            emptyList<String>(),
            restored[0].listApps
        )
    }

    // ────────────────────────────────────────────────────────────
    //  #5 与 daysOfWeek 同存：两个字段都正确往返
    // ────────────────────────────────────────────────────────────

    @Test
    fun roundTrip_listAppsAndDaysOfWeek_coexist() {
        val original = listOf(
            seg(
                name = "工作日深夜",
                listApps = listOf("com.tencent.mobileqq"),
                // 周一、周二（非空且非全 7 天，避免 normalize 折叠为 null）
                daysOfWeek = listOf(2, 3)
            )
        )

        val json = TimeRuleManager.segmentsToJson(original)
        val restored = TimeRuleManager.parseSegmentsFromJson(json)

        assertEquals(1, restored.size)
        val segment = restored[0]
        assertEquals(
            "listApps 应正确往返",
            listOf("com.tencent.mobileqq"),
            segment.listApps
        )
        assertEquals(
            "daysOfWeek 应与 listApps 同存并正确往返",
            listOf(2, 3),
            segment.daysOfWeek
        )
    }
}
