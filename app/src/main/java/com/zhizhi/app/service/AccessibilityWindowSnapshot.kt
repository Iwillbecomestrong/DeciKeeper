package com.zhizhi.app.service

enum class AccessibilityWindowSnapshotStatus {
    COMPLETE,
    PARTIAL,
    FAILED
}

data class AccessibilityWindowSnapshot(
    val capturedAtMs: Long,
    val status: AccessibilityWindowSnapshotStatus,
    val packages: Set<String>,
    val unresolvedWindowCount: Int,
    val totalWindowCount: Int,
    val failureClass: String? = null
)
