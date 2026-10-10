package com.decikeeper.app.logging

/**
 * 最近一次前台判定的精简证据，只用于给 A 级事件（如 LOCK_RESULT）附上一行"为什么"。
 *
 * 只写不读于业务：MonitorService 每个判定周期更新，日志代码在写事件时读取；
 * 任何放行或拦截决策都不得读取它（AGENT_CORE.md 取证红线）。
 */
object LastForegroundEvidence {

    private class Snapshot(val packageName: String, val source: String, val reason: String)

    @Volatile
    private var snapshot: Snapshot? = null

    fun update(packageName: String?, source: String, reason: String) {
        snapshot = Snapshot(packageName ?: "NONE", source, reason)
    }

    /** 渲染为 `fg:<pkg> src:<SRC> reason:<CODE>`，值内空白替换为下划线；尚无记录时返回 null。 */
    fun render(): String? = snapshot?.let {
        "fg:${clean(it.packageName)} src:${clean(it.source)} reason:${clean(it.reason)}"
    }

    internal fun clearForTesting() {
        snapshot = null
    }

    private fun clean(value: String): String = value.trim().replace(Regex("\\s+"), "_")
}
