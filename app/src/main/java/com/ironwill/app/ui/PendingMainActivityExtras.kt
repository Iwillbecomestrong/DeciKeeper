/**
 * =================================================================================
 * [Context Header]
 * * @file: PendingMainActivityExtras.kt
 * * @role: Domain Skill
 * * @description: 在"MainActivityEntry → PrivacyConsentActivity → MainActivity"链
 *                路上临时保存/回放 MainActivity 的 extras。
 * * @responsibilities:
 * 1. 保存：MainActivityEntry.redirectToConsent() 在跳同意页前，把 baseIntent 的
 *    extras（按白名单逐字段复制为 JSON）与 flags 写入独立 prefs。
 * 2. 消费：PrivacyConsentActivity 在同意后读取该 prefs，构造携带原始 extras 的
 *    MainActivity Intent，然后清空 prefs。
 * 3. 兜底：MainActivity.onCreate 入口防线也做一次消费，确保即使同意页未走
 *    goToMainAndFinish 也能"重放"最后一次待回放的语义。
 * * @context:
 * - 上游: MainActivityEntry, PrivacyConsentActivity, MainActivity
 * * @constraints (必读):
 * 1. **绝对不要用 Java Serializable 序列化 Bundle**（Android Bundle 只 implements
 *    Cloneable, Parcelable，没有 Serializable；之前 round 3 的实现有 bug，已在本
 *    轮修复为显式白名单 + JSON）。
 * 2. extras 仅支持白名单基础类型（String / Boolean / Int / Long）+ 不存（见
 *    [SUPPORTED_TYPES]）。不存 Parcelable / Serializable / Bundle / Intent 等复杂
 *    类型；遇到这些就跳过该字段（并 warn 日志），绝不阻塞主页启动。
 * 3. 写入/读取失败必须回退为"无待回放"，绝不能阻塞主页启动。
 * 4. 同意或拒绝后只允许消费一次，消费完立即清空（防止下次冷启动意外触发）。
 * 5. 该 prefs 文件自身**不参与**备份（参见 backup_rules.xml 的 exclude 路径）。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * 用于在同意页前后传递 MainActivity 的 extras / flags。
 *
 * 之所以走 prefs 而不是直接传 Intent：MainActivityEntry 可能在 Service / Receiver /
 * Application context 中调用，而 PrivacyConsentActivity 又是重新构造的 Intent，
 * intent extras 跨"两次 startActivity"无法保留。
 */
object PendingMainActivityExtras {

    private const val TAG = "PendingMainActivityExtras"

    /** 独立 prefs 文件，不参与备份。 */
    const val PREFS_NAME: String = "pending_main_activity_extras"

    /** extras 的 JSON 序列化字符串（key → 白名单字段 map） */
    private const val KEY_EXTRAS_JSON: String = "extras_json"

    /** baseIntent.flags 的低 32 位 */
    private const val KEY_FLAGS: String = "flags"

    /** 上游 component（Class 全名） */
    private const val KEY_COMPONENT: String = "component"

    /** 时间戳，便于审计与排查 */
    private const val KEY_SAVED_AT_MS: String = "saved_at_ms"

    /**
     * 白名单基础类型。每条 extras 在 save 时仅当 key 不在此排除列表且 value 类型是
     * 这四种之一时才被序列化；consume 时反序列化并 put 回 Intent。
     */
    private val SUPPORTED_TYPES = setOf(
        String::class.java,
        Boolean::class.java,
        java.lang.Integer::class.java,
        java.lang.Long::class.java
    )

    fun save(context: Context, baseIntent: Intent) {
        // TODO(round-4 MAJOR-4): 当前 emulator 不可用,save/consume 的白名单 JSON
        // 序列化路径没有 androidTest 覆盖。 待 emulator-tester 在真机上验证:
        // pm clear → 通知中心点击带 extras 的通知 →
        // 期望同意页后跳到主页时 extras 仍被还原,
        // 而不是抛 NotSerializableException (round 3 的实现就是这样崩的)。
        Log.w(
            TAG,
            "TODO(round-4 MAJOR-4): needs androidTest on real device " +
                "for JSON-whitelist extras replay path " +
                "(replaces round 3 Serializable-Bundle path; BLOCKED_NEEDS_REAL_DEVICE)"
        )
        try {
            val prefs = prefs(context)
            val bundle = baseIntent.extras
            val flags = baseIntent.flags
            val extrasJson = if (bundle != null) bundleToWhitelistJson(bundle) else ""
            prefs.edit()
                .putString(KEY_COMPONENT, baseIntent.component?.className ?: "")
                .putInt(KEY_FLAGS, flags)
                .putString(KEY_EXTRAS_JSON, extrasJson)
                .putLong(KEY_SAVED_AT_MS, System.currentTimeMillis())
                .apply()
            Log.i(
                TAG,
                "save: pending extras saved (component=${baseIntent.component?.className}, " +
                    "flags=$flags, hasExtras=${bundle != null}, jsonLen=${extrasJson.length})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "save: failed", e)
        }
    }

    fun hasPending(context: Context): Boolean {
        return try {
            prefs(context).contains(KEY_COMPONENT) ||
                prefs(context).contains(KEY_EXTRAS_JSON) ||
                prefs(context).contains(KEY_FLAGS)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 读取并清空 pending extras，返回重建后的 MainActivity Intent。
     * 若没有 pending 或读取失败，返回 null。
     */
    fun consume(context: Context): Intent? {
        return try {
            val prefs = prefs(context)
            if (!prefs.contains(KEY_COMPONENT) && !prefs.contains(KEY_EXTRAS_JSON)) {
                return null
            }
            val componentClassName = prefs.getString(KEY_COMPONENT, "") ?: ""
            val flags = prefs.getInt(KEY_FLAGS, 0)
            val extrasJson = prefs.getString(KEY_EXTRAS_JSON, "") ?: ""
            clear(context)
            if (componentClassName.isEmpty() || componentClassName != MainActivity::class.java.name) {
                Log.w(TAG, "consume: unexpected component class=$componentClassName, skip replay")
                return null
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(flags)
            }
            if (extrasJson.isNotEmpty()) {
                applyWhitelistJsonToIntent(extrasJson, intent)
            }
            Log.i(TAG, "consume: replayed pending extras (flags=$flags, hasExtras=${extrasJson.isNotEmpty()})")
            intent
        } catch (e: Exception) {
            Log.w(TAG, "consume: failed, clearing prefs", e)
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit()
                .remove(KEY_EXTRAS_JSON)
                .remove(KEY_FLAGS)
                .remove(KEY_COMPONENT)
                .remove(KEY_SAVED_AT_MS)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "clear: failed", e)
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 把 Bundle 按白名单逐字段复制为 JSON 字符串。
     *
     * JSON 格式：
     * ```
     * { "k1": { "t": "string" | "bool" | "int" | "long", "v": <value> }, ... }
     * ```
     *
     * 非白名单 key / value 类型会被跳过（Log.w 标记），保证 save 永不抛异常。
     */
    private fun bundleToWhitelistJson(bundle: Bundle): String {
        val obj = JSONObject()
        val keys = bundle.keySet()
        for (key in keys) {
            if (key.isNullOrEmpty()) continue
            // 防御：避免存到 IGNORE 类的内部 key。
            val value: Any = bundle.get(key) ?: continue
            val valueClass: Class<*> = value.javaClass
            val supportedClass = SUPPORTED_TYPES.firstOrNull { it.isAssignableFrom(valueClass) }
            if (supportedClass == null) {
                Log.w(
                    TAG,
                    "bundleToWhitelistJson: skip key='$key' " +
                        "unsupported type=${valueClass.simpleName}"
                )
                continue
            }
            val entry = JSONObject()
            when (supportedClass) {
                String::class.java -> {
                    entry.put("t", "string")
                    entry.put("v", value as String)
                }
                Boolean::class.java -> {
                    entry.put("t", "bool")
                    entry.put("v", value as Boolean)
                }
                java.lang.Integer::class.java -> {
                    entry.put("t", "int")
                    entry.put("v", value as Int)
                }
                java.lang.Long::class.java -> {
                    entry.put("t", "long")
                    entry.put("v", value as Long)
                }
            }
            obj.put(key, entry)
        }
        return obj.toString()
    }

    /**
     * 把 save 出来的 JSON 还原到 Intent 的 extras（白名单字段）。
     * 遇到无法识别的类型，warn 后跳过，不会抛异常。
     */
    private fun applyWhitelistJsonToIntent(json: String, intent: Intent) {
        val obj = JSONObject(json)
        val names = obj.keys()
        while (names.hasNext()) {
            val key = names.next() ?: continue
            val entry = obj.optJSONObject(key) ?: continue
            val t = entry.optString("t")
            when (t) {
                "string" -> intent.putExtra(key, entry.optString("v"))
                "bool" -> intent.putExtra(key, entry.optBoolean("v"))
                "int" -> intent.putExtra(key, entry.optInt("v"))
                "long" -> intent.putExtra(key, entry.optLong("v"))
                else -> Log.w(TAG, "applyWhitelistJsonToIntent: skip key='$key' unknown t='$t'")
            }
        }
    }
}
