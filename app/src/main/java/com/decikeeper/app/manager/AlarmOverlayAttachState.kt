package com.decikeeper.app.manager

/**
 * 闹钟覆盖层视图的挂载状态。
 *
 * 覆盖层在 inline（挂在 AlarmActivity 内）与独立窗口（TYPE_ACCESSIBILITY_OVERLAY）
 * 之间切换时，需要先把 view 从原父容器摘下、再挂到目标 WindowManager。这两步之间
 * 一旦 addView 失败且没有回滚，view 就会停在「对象存在但没有 parent」的状态——此后
 * 针对它的所有渲染都不会出现在任何窗口上，用户看到白屏，而闹钟会话仍然活跃，
 * 表现为「闹钟关了但界面出不来、也退不出去」。
 *
 * 本文件把该判定抽成纯函数，便于 JVM 单测守护（见 AlarmOverlayAttachStateTest）。
 * 覆盖层代码只负责探测事实（view 是否存在、是否有 parent），判断一律走这里。
 */
internal enum class OverlayAttachState {
    /** 没有 view：尚未创建，或已被 forceReset 清理。 */
    NO_VIEW,

    /** view 已挂在某个窗口上，可正常渲染。 */
    ATTACHED,

    /** view 对象存在但没有父容器——必须收殓的非法态。 */
    DETACHED
}

/**
 * 由调用方探测到的事实推导挂载状态。
 *
 * @param viewExists `LockWindowManager.getAlarmView() != null`
 * @param viewHasParent `view.parent != null`
 */
internal fun classifyOverlayAttachState(
    viewExists: Boolean,
    viewHasParent: Boolean
): OverlayAttachState {
    if (!viewExists) return OverlayAttachState.NO_VIEW
    return if (viewHasParent) OverlayAttachState.ATTACHED else OverlayAttachState.DETACHED
}

/** 一次「挂到目标窗口」尝试的结果。 */
internal enum class AttachAttemptOutcome {
    /** 已挂到目标窗口。 */
    SWITCHED,

    /** 目标挂载失败，但已回滚到原父容器——界面仍然可见。 */
    ROLLED_BACK,

    /** 目标挂载失败且回滚也失败——必须由调用点收殓。 */
    DETACHED
}

/**
 * 由两次挂载动作的成败推导结果。
 *
 * @param targetAttachSucceeded `target.addView(view, params)` 是否成功
 * @param rollbackSucceeded 失败后回滚到原父容器是否成功
 */
internal fun resolveAttachAttemptOutcome(
    targetAttachSucceeded: Boolean,
    rollbackSucceeded: Boolean
): AttachAttemptOutcome = when {
    targetAttachSucceeded -> AttachAttemptOutcome.SWITCHED
    rollbackSucceeded -> AttachAttemptOutcome.ROLLED_BACK
    else -> AttachAttemptOutcome.DETACHED
}

/** DETACHED 状态下的处置方式。 */
internal enum class DetachedRecovery {
    /** 无需处置（非 DETACHED）。 */
    NONE,

    /** 会话仍活跃：必须重新附加/重建，否则用户看不到任何界面，也无法退出。 */
    REBUILD,

    /** 会话已结束：只清理残留状态，不做无意义重建。 */
    CLEAR_ONLY
}

/**
 * 决定 DETACHED 视图的处置方式。
 *
 * 会话活跃时不能只清理——那会让用户既看不到完成态、也点不到关闭按钮。
 */
internal fun resolveDetachedRecovery(
    state: OverlayAttachState,
    isSessionActive: Boolean
): DetachedRecovery {
    if (state != OverlayAttachState.DETACHED) return DetachedRecovery.NONE
    return if (isSessionActive) DetachedRecovery.REBUILD else DetachedRecovery.CLEAR_ONLY
}
