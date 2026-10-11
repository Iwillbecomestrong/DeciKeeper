package com.decikeeper.app.logging

/**
 * 进程启动行：每个进程写一次，替代以前每行重复携带的运行环境信息，
 * 方便反馈时核对机型、系统和应用版本（HISTORY.md 约定的 4 要素之一）。
 */
object ProcessStartLine {
    const val TAG = "Process"

    fun format(versionName: String?, manufacturer: String, model: String, androidRelease: String, sdkInt: Int): String =
        "[PROCESS_START] version=${clean(versionName ?: "unknown")} device=${clean("$manufacturer/$model")} " +
            "android=${clean(androidRelease)} sdk=$sdkInt"

    private val whitespace = Regex("\\s+")

    private fun clean(value: String): String = value.trim().replace(whitespace, "_")
}
