package com.zhizhi.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSessionIsolationContractTest {

    @Test
    fun rejectedTriggerCleanupDoesNotTearDownRunningAlarmSession() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val cleanup = receiver
            .substringAfter("private fun cleanupStaleAlarm")
            .substringBefore("private data class ValidateResult")

        assertFalse(
            "a rejected stale trigger must not stop the shared vibration service",
            cleanup.contains("AlarmVibrationService.stop(context)")
        )
        assertFalse(
            "a rejected stale trigger must not clear the authoritative active alarm session",
            cleanup.contains("AlarmScheduler.clearAlarmActive(context)")
        )
        assertFalse(
            "a rejected stale trigger must not dismiss the current alarm window bookkeeping",
            cleanup.contains("AlarmWindowManager.dismiss()")
        )
    }

    @Test
    fun recoveryCallbackMustMatchTheActiveAlarmId() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val recovery = receiver
            .substringAfter("if (action == AlarmScheduler.ACTION_ALARM_RECOVERY)")
            .substringBefore("// ----- 重启安全网")

        assertTrue(
            "recovery must read the authoritative active alarm id",
            recovery.contains("AlarmScheduler.getActiveAlarmId(context)")
        )
        assertTrue(
            "a stale recovery callback must be rejected when its id differs from the active alarm",
            recovery.contains("activeAlarmId == alarmId") || recovery.contains("activeAlarmId != alarmId")
        )
    }

    @Test
    fun rebootSafetyNetCallbackMustMatchTheActiveAlarmId() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val safetyNet = receiver
            .substringAfter("if (action == AlarmScheduler.ACTION_ALARM_REBOOT_SAFETY_NET)")
            .substringBefore("// ----- 正常闹钟触发 / 休眠")

        assertTrue(
            "reboot safety-net must read the authoritative active alarm id",
            safetyNet.contains("AlarmScheduler.getActiveAlarmId(context)")
        )
        assertTrue(
            "a stale reboot safety-net callback must be rejected when its id differs from the active alarm",
            safetyNet.contains("activeAlarmId == alarmId") || safetyNet.contains("activeAlarmId != alarmId")
        )
    }

    @Test
    fun vibrationServiceDestructionDoesNotEndTheBusinessAlarmSession() {
        val service = source("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")
        val onDestroy = service
            .substringAfter("override fun onDestroy()")
            .substringBefore("override fun onBind")

        assertFalse(
            "Android destroying the vibration service must not declare the alarm challenge finished",
            onDestroy.contains("AlarmScheduler.clearAlarmActive(this)")
        )
    }

    @Test
    fun explicitChallengeDismissStillOwnsAlarmSessionTeardown() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        val dismiss = overlay
            .substringAfter("private fun dismissAlarmOverlay(reason: String, snooze: Boolean)")
            .substringBefore("/**\n     * [DO NOT DELETE] 强制重置闹钟覆盖层状态")

        assertTrue(
            "explicit challenge completion must still clear the active alarm session",
            dismiss.contains("AlarmScheduler.clearAlarmActive(ctx)")
        )
        assertTrue(
            "explicit challenge completion must still stop the vibration service",
            dismiss.contains("AlarmVibrationService.stop(ctx)")
        )
    }

    @Test
    fun scheduledTriggerStartsFreshSessionButRecoveryPathsReuseSession() {
        val receiver = source("app/src/main/java/com/zhizhi/app/receiver/AlarmReceiver.kt")
        val guardian = source("app/src/main/java/com/zhizhi/app/service/AlarmGuardianService.kt")
        val backup = receiver
            .substringAfter("if (action == AlarmScheduler.ACTION_ALARM_BACKUP || action == AlarmScheduler.ACTION_ALARM_BACKUP2)")
            .substringBefore("// ----- 恢复闹钟")

        assertTrue(
            "normal scheduled trigger must choose a new session only for a new occurrence",
            receiver.contains("AlarmScheduler.isSameActiveOccurrence(context, alarmId, receivedToken)") &&
                receiver.contains("AlarmScheduler.setAlarmActive(context, alarmId, newSession = newSession)") &&
                receiver.contains("AlarmWindowManager.show(context, alarmId, newSession = newSession)")
        )
        assertTrue(
            "guardian scheduled trigger must choose a new session only for a new occurrence",
            guardian.contains("AlarmScheduler.isSameActiveOccurrence(this, alarmId, scheduleToken)") &&
                guardian.contains("AlarmScheduler.setAlarmActive(this, alarmId, newSession = newSession)") &&
                guardian.contains("AlarmWindowManager.show(this, alarmId, newSession = newSession)")
        )
        assertTrue(
            "a validated backup occurrence must start a fresh session",
            backup.contains("AlarmScheduler.setAlarmActive(context, alarmId, newSession = true)") &&
                backup.contains("AlarmWindowManager.show(context, alarmId, newSession = true)")
        )
        assertTrue(
            "the scheduler must expose an explicit new-session boundary",
            source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt").let {
                it.contains("newSession: Boolean = false") &&
                    it.contains("fun isSameActiveOccurrence") &&
                    it.contains("fun markChallengeCompleted") &&
                    it.contains("fun isChallengeCompleted")
            }
        )
    }

    @Test
    fun completionCooldownMustBePersistentAndChallengeInputMustBeIdempotent() {
        val scheduler = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        assertTrue(scheduler.contains("setCompletionCooldownUntil"))
        assertTrue(scheduler.contains("getCompletionCooldownUntil"))
        assertTrue(scheduler.contains("clearCompletionCooldown"))
        assertTrue(overlay.contains("markChallengeCompleted(ctx, true, completionCooldownUntil)"))
        assertTrue(overlay.contains("AlarmScheduler.clearCompletionCooldown(ctx)"))
        assertTrue(
            "an expired persisted cooldown must restore the completed UI without resubmitting",
            overlay.contains("showCompletedChallengeState(view)") &&
                overlay.contains("延迟可能已在覆盖层重建前到期") &&
                overlay.contains("AlarmScheduler.isChallengeCompleted(alarmContext)")
        )
        assertTrue(
            "same-session overlay reentry must preserve challenge progress and review data",
            overlay.contains("val preserveSessionState = !newSession") &&
                overlay.contains("if (!preserveSessionState)")
        )
        assertTrue(
            "QR callbacks must be invalidated when the Activity session generation changes",
            source("app/src/main/java/com/zhizhi/app/ui/AlarmActivity.kt").contains("callbackGeneration == scanGeneration") &&
                overlay.contains("callbackGeneration != alarmSessionGeneration")
        )
        assertTrue(
            "cooldown restoration must hide every challenge card",
            overlay.contains("cv_alarm_qr") &&
                overlay.contains("cv_alarm_math") &&
                overlay.contains("cv_alarm_poem") &&
                overlay.contains("cv_alarm_word") &&
                overlay.contains("cv_alarm_cloze") &&
                overlay.contains("cv_alarm_custom")
        )
    }

    @Test
    fun singleStageCooldownIndicatorMustStayVisibleOutsideScrollablePanels() {
        val layout = source("app/src/main/res/layout/window_alarm_overlay.xml")
        val cooldown = layout.indexOf("android:id=\"@+id/tv_stage_cooldown\"")
        val review = layout.indexOf("android:id=\"@+id/layout_alarm_review\"")
        val actions = layout.indexOf("android:id=\"@+id/layout_alarm_actions\"")
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        assertTrue("cooldown indicator must be declared after review and before bottom actions", cooldown > review && cooldown < actions)
        assertTrue("cooldown display must use the fixed indicator", overlay.contains("findViewById<TextView>(R.id.tv_stage_cooldown)"))
        assertFalse("cooldown display must not depend on the optional review panel", overlay.contains("R.id.tv_review_cooldown"))
    }

    @Test
    fun completedMinimumIntensityMustSurviveServiceRestart() {
        val scheduler = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val service = source("app/src/main/java/com/zhizhi/app/service/AlarmVibrationService.kt")

        assertTrue("attenuation state must be persisted in the active alarm session", scheduler.contains("setAlarmAttenuated") && scheduler.contains("isAlarmAttenuated"))
        assertTrue("service restart must restore the persisted attenuation state", service.contains("isAttenuated = AlarmScheduler.isAlarmAttenuated(this)"))
        assertTrue("completion attenuation must write the persisted state", service.contains("AlarmScheduler.setAlarmAttenuated(this, true)"))
        assertTrue("stage 2 restart must clear the prior stage attenuation state", service.contains("AlarmScheduler.setAlarmAttenuated(this, false)"))
    }

    @Test
    fun completedTwoStageSessionMustRestoreFinalStateInsteadOfSecondStageChallenges() {
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")
        val completedGuard = "if (isTwoStageUnlock && AlarmScheduler.isChallengeCompleted(alarmContext))"

        assertTrue("two-stage recovery must check completion before rendering stage 2", overlay.contains(completedGuard))
        assertTrue(
            "two-stage recovery must restore the close/review state",
            overlay.contains(completedGuard) &&
                overlay.substringAfter(completedGuard).substringBefore("if (!isAlarmFirstStagePassed)").contains("showCompletedChallengeState(view)")
        )
    }

    @Test
    fun completedReviewDataMustBeSessionScopedAndRestorable() {
        val scheduler = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        assertTrue("review payload must have an active-session persistence API", scheduler.contains("setCompletedReviewData") && scheduler.contains("getCompletedReviewData"))
        assertTrue("review entries must be persisted as they are settled", overlay.contains("persistSessionReviewData()"))
        assertTrue("completed UI restoration must reload review entries before deciding visibility", overlay.contains("restoreSessionReviewData(view.context)"))
        assertTrue("new sessions must discard the previous review payload", scheduler.contains("remove(KEY_ACTIVE_REVIEW_DATA)"))
    }

    @Test
    fun completionAndCooldownMustUseOneAtomicSchedulerWrite() {
        val scheduler = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val overlay = source("app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt")

        assertTrue("scheduler completion write must accept the cooldown deadline", scheduler.contains("cooldownUntilMs: Long = 0L"))
        assertTrue("overlay must pass the same deadline when marking completion", overlay.contains("markChallengeCompleted(ctx, true, completionCooldownUntil)"))
        assertTrue("a late challenge callback must not renew a completed session", overlay.contains("AlarmScheduler.isChallengeCompleted(ctx)"))
        assertTrue("cooldown display must derive remaining seconds from the absolute deadline", overlay.contains("cooldownUntilMs - System.currentTimeMillis()"))
    }

    @Test
    fun fallbackTriggersCarryOccurrenceIdentity() {
        val scheduler = source("app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt")
        val worker = source("app/src/main/java/com/zhizhi/app/worker/AlarmWorker.kt")
        assertTrue(scheduler.contains("AlarmWorker.KEY_SCHEDULE_TOKEN") && scheduler.contains("AlarmWorker.KEY_SCHEDULED_AT"))
        assertTrue(worker.contains("KEY_SCHEDULE_TOKEN") && worker.contains("KEY_SCHEDULED_AT"))
        assertTrue(scheduler.contains("putExtra(EXTRA_SCHEDULE_TOKEN, scheduleToken)"))
        assertTrue(
            "watchdog must capture the occurrence token when it starts",
            source("app/src/main/java/com/zhizhi/app/service/AlarmWatchdogService.kt")
                .contains("EXTRA_SCHEDULE_TOKEN") &&
                source("app/src/main/java/com/zhizhi/app/service/AlarmWatchdogService.kt")
                    .contains("scheduleToken")
        )
    }

    private fun source(relativePath: String): String =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find $relativePath")
}
