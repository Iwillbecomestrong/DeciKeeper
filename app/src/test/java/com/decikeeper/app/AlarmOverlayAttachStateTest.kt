package com.decikeeper.app

import com.decikeeper.app.manager.AttachAttemptOutcome
import com.decikeeper.app.manager.DetachedRecovery
import com.decikeeper.app.manager.OverlayAttachState
import com.decikeeper.app.manager.classifyOverlayAttachState
import com.decikeeper.app.manager.resolveAttachAttemptOutcome
import com.decikeeper.app.manager.resolveDetachedRecovery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 闹钟覆盖层挂载状态的纯函数契约。
 *
 * 关联真机缺陷：长按电源键 → 关机菜单 → 覆盖层切窗口模式 → 窗口丢失（view 存在但
 * parent 为 null）→ 扫码成功后白屏且闹钟无法关闭。此处锁死"游离视图必须被收殓"这条不变量。
 */
class AlarmOverlayAttachStateTest {

    @Test
    fun viewNotAttachedToWindowIsDetached() {
        assertEquals(
            OverlayAttachState.DETACHED,
            classifyOverlayAttachState(viewExists = true, viewAttachedToWindow = false)
        )
    }

    @Test
    fun viewAttachedToWindowIsAttached() {
        assertEquals(
            OverlayAttachState.ATTACHED,
            classifyOverlayAttachState(viewExists = true, viewAttachedToWindow = true)
        )
    }

    @Test
    fun missingViewIsNoView() {
        assertEquals(
            OverlayAttachState.NO_VIEW,
            classifyOverlayAttachState(viewExists = false, viewAttachedToWindow = false)
        )
    }

    @Test
    fun detachedViewInActiveSessionMustBeRebuilt() {
        // 会话活跃时只清理会让用户既看不到完成态也点不到关闭按钮——必须重建
        assertEquals(
            DetachedRecovery.REBUILD,
            resolveDetachedRecovery(OverlayAttachState.DETACHED, isSessionActive = true)
        )
    }

    @Test
    fun detachedViewInFinishedSessionIsOnlyCleared() {
        assertEquals(
            DetachedRecovery.CLEAR_ONLY,
            resolveDetachedRecovery(OverlayAttachState.DETACHED, isSessionActive = false)
        )
    }

    @Test
    fun nonDetachedStatesNeedNoRecovery() {
        assertEquals(
            DetachedRecovery.NONE,
            resolveDetachedRecovery(OverlayAttachState.ATTACHED, isSessionActive = true)
        )
        assertEquals(
            DetachedRecovery.NONE,
            resolveDetachedRecovery(OverlayAttachState.NO_VIEW, isSessionActive = true)
        )
    }

    @Test
    fun successfulTargetAttachWins() {
        assertEquals(
            AttachAttemptOutcome.SWITCHED,
            resolveAttachAttemptOutcome(targetAttachSucceeded = true, rollbackSucceeded = false)
        )
    }

    @Test
    fun failedAttachWithSuccessfulRollbackKeepsUiVisible() {
        assertEquals(
            AttachAttemptOutcome.ROLLED_BACK,
            resolveAttachAttemptOutcome(targetAttachSucceeded = false, rollbackSucceeded = true)
        )
    }

    @Test
    fun failedAttachAndFailedRollbackNeedsRebuild() {
        assertEquals(
            AttachAttemptOutcome.DETACHED,
            resolveAttachAttemptOutcome(targetAttachSucceeded = false, rollbackSucceeded = false)
        )
    }
}
