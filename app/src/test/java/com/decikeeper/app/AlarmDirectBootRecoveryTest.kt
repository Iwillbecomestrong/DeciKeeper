package com.decikeeper.app

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.UserManager
import com.decikeeper.app.receiver.AlarmReceiver
import com.decikeeper.app.receiver.BootReceiver
import com.decikeeper.app.manager.AlarmBootstrapStorage
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.service.AlarmVibrationService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDirectBootRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_bootstrap_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowAlarmManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BootReceiver.userPresentOverlayRestorer = null
        MyApplication.directBootRecoveryStarter = null
        CoreService.isServiceActive = false
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
        BootReceiver.userPresentOverlayRestorer = null
        MyApplication.directBootRecoveryStarter = null
        CoreService.isServiceActive = false
    }

    @Test
    fun `Direct Boot recovery schedules future alarm from device protected bootstrap`() {
        val triggerAt = System.currentTimeMillis() + 60 * 60 * 1000L
        AlarmBootstrapStorage.replace(
            context,
            listOf(
                AlarmBootstrapStorage.Entry(
                    alarmId = 7,
                    nextTriggerAtMillis = triggerAt,
                    enabled = true,
                    scheduleToken = "direct-boot-token"
                )
            )
        )

        AlarmScheduler.scheduleAllForDirectBoot(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduled = Shadows.shadowOf(alarmManager).scheduledAlarms.singleOrNull()
        assertNotNull("Direct Boot must re-register the future AlarmManager trigger", scheduled)
        assertEquals(triggerAt, scheduled!!.triggerAtTime)
        assertEquals(AlarmScheduler.ACTION_ALARM_TRIGGER, Shadows.shadowOf(scheduled.operation).savedIntent.action)
        assertEquals(7, Shadows.shadowOf(scheduled.operation).savedIntent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1))
        assertEquals(
            "direct-boot-token",
            Shadows.shadowOf(scheduled.operation).savedIntent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN)
        )
    }

    @Test
    fun `failed DPS bootstrap write prevents normal AlarmManager publication`() {
        AlarmBootstrapStorage.commitResultForTest = { false }
        try {
            val scheduled = AlarmScheduler.scheduleNext(
                context,
                AlarmScheduler.AlarmEntry(
                    id = 7,
                    hour = 7,
                    minute = 30,
                    enabled = true,
                    days = BooleanArray(7) { true }
                )
            )

            assertFalse(scheduled)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
        } finally {
            AlarmBootstrapStorage.commitResultForTest = null
        }
    }

    @Test
    fun `Direct Boot active session stays entirely in device protected storage`() {
        AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "direct-boot-token")

        assertEquals(true, AlarmScheduler.isAlarmActive(context))
        assertEquals(7, AlarmScheduler.getActiveAlarmId(context))
        assertEquals("direct-boot-token", AlarmScheduler.getActiveScheduleToken(context))
        assertEquals(
            "[]",
            context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
                .getString("alarms_json", "[]")
        )
    }

    @Test
    fun `LOCKED_BOOT_COMPLETED re-registers future alarm without credential storage`() {
        val triggerAt = System.currentTimeMillis() + 60 * 60 * 1000L
        AlarmBootstrapStorage.replace(
            context,
            listOf(AlarmBootstrapStorage.Entry(7, triggerAt, true, "boot-token"))
        )

        com.decikeeper.app.receiver.BootReceiver().onReceive(
            context,
            Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertEquals(1, Shadows.shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `locked trigger promotes matching bootstrap to active session and starts direct boot service`() {
        val triggerAt = System.currentTimeMillis()
        AlarmBootstrapStorage.replace(
            context,
            listOf(AlarmBootstrapStorage.Entry(7, triggerAt, true, "boot-token"))
        )
        val userManager = context.getSystemService(UserManager::class.java)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        try {
            val recordingContext = RecordingContext(context)
            AlarmReceiver().onReceive(
                recordingContext,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = AlarmScheduler.ACTION_ALARM_TRIGGER
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, 7)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN, "boot-token")
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT_MILLIS, triggerAt)
                }
            )
            assertTrue(AlarmScheduler.isAlarmActive(context))
            assertEquals(7, AlarmScheduler.getActiveAlarmId(context))
            val serviceIntent = recordingContext.foregroundServiceIntents.single()
            assertEquals(AlarmVibrationService::class.java.name, serviceIntent.component?.className)
            assertTrue(serviceIntent.getBooleanExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, false))
            assertEquals(7, serviceIntent.getIntExtra(AlarmVibrationService.EXTRA_ALARM_ID, -1))
            assertEquals("boot-token", serviceIntent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN))
        } finally {
            Shadows.shadowOf(userManager).setUserUnlocked(true)
        }
    }

    @Test
    fun `locked trigger rejects token mismatch without active side effect`() {
        val triggerAt = System.currentTimeMillis()
        AlarmBootstrapStorage.replace(
            context,
            listOf(AlarmBootstrapStorage.Entry(7, triggerAt, true, "expected-token"))
        )
        val userManager = context.getSystemService(UserManager::class.java)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        try {
            AlarmReceiver().onReceive(
                context,
                directBootIntent(7, "stale-token", triggerAt)
            )
            assertFalse(AlarmScheduler.isAlarmActive(context))
        } finally {
            Shadows.shadowOf(userManager).setUserUnlocked(true)
        }
    }

    @Test
    fun `locked trigger rejects early and late delivery using injected clock`() {
        val triggerAt = 10_000L
        val entry = AlarmBootstrapStorage.Entry(7, triggerAt, true, "time-token")
        assertFalse(
            AlarmScheduler.validateDirectBootTrigger(
                entry = entry,
                receivedToken = "time-token",
                receivedScheduledAt = triggerAt,
                now = triggerAt - AlarmScheduler.DIRECT_BOOT_EARLY_TOLERANCE_MS - 1
            ).accepted
        )
        assertFalse(
            AlarmScheduler.validateDirectBootTrigger(
                entry = entry,
                receivedToken = "time-token",
                receivedScheduledAt = triggerAt,
                now = triggerAt + AlarmScheduler.DIRECT_BOOT_LATE_TOLERANCE_MS + 1
            ).accepted
        )
    }

    @Test
    fun `direct boot validation rejects disabled or mismatched scheduled bootstrap`() {
        val triggerAt = 10_000L
        assertFalse(
            AlarmScheduler.validateDirectBootTrigger(
                entry = AlarmBootstrapStorage.Entry(7, triggerAt, false, "time-token"),
                receivedToken = "time-token",
                receivedScheduledAt = triggerAt,
                now = triggerAt
            ).accepted
        )
        assertFalse(
            AlarmScheduler.validateDirectBootTrigger(
                entry = AlarmBootstrapStorage.Entry(7, triggerAt, true, "time-token"),
                receivedToken = "time-token",
                receivedScheduledAt = triggerAt + 1_000L,
                now = triggerAt
            ).accepted
        )
    }

    @Test
    fun `same locked token is idempotent and does not replace active session`() {
        assertTrue(AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "same-token"))
        assertFalse(AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "same-token"))
        assertEquals(7, AlarmScheduler.getActiveAlarmId(context))
        assertEquals("same-token", AlarmScheduler.getActiveScheduleToken(context))
    }

    @Test
    fun `direct boot vibration service does not parse credential alarm configuration`() {
        AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "service-token")
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("alarms_json", "not-json")
            .commit()
        val userManager = context.getSystemService(UserManager::class.java)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        val controller = Robolectric.buildService(AlarmVibrationService::class.java).create()
        try {
            controller.get().onStartCommand(
                Intent(context, AlarmVibrationService::class.java).apply {
                    putExtra(AlarmVibrationService.EXTRA_ALARM_ID, 7)
                    putExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, true)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN, "service-token")
                },
                0,
                1
            )
            assertEquals("VIBRATE_ONLY", AlarmVibrationService.currentAlarmMode)
        } finally {
            controller.destroy()
            Shadows.shadowOf(userManager).setUserUnlocked(true)
        }
    }

    @Test
    fun `recurring direct boot trigger advances bootstrap to a new token and next time`() {
        val now = 1_700_000_000_000L
        val entry = AlarmBootstrapStorage.Entry(
            alarmId = 7,
            nextTriggerAtMillis = now,
            enabled = true,
            scheduleToken = "old-token",
            hour = 7,
            minute = 30,
            daysMask = 0b1111111
        )
        AlarmBootstrapStorage.replace(context, listOf(entry))

        assertTrue(AlarmScheduler.advanceDirectBootSchedule(context, entry, now))

        val next = AlarmBootstrapStorage.find(context, 7)
        assertNotNull(next)
        assertTrue(next!!.nextTriggerAtMillis > now)
        assertTrue(next.scheduleToken.isNotBlank())
        assertTrue(next.scheduleToken != "old-token")
        assertEquals(1, Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms.size)
    }

    @Test
    fun `user present reloads CE schedule and upgrades active direct boot session`() {
        val alarm = AlarmScheduler.AlarmEntry(
            id = 7,
            hour = 7,
            minute = 30,
            enabled = true,
            days = BooleanArray(7) { true },
            scheduleToken = "ce-token"
        )
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("alarms_json", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("id", alarm.id)
                put("hour", alarm.hour)
                put("minute", alarm.minute)
                put("enabled", alarm.enabled)
                put("days", org.json.JSONArray(alarm.days.toList()))
                put("scheduleToken", alarm.scheduleToken)
            }).toString())
            .putInt("alarm_next_id", 8)
            .commit()
        AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "ce-token")
        val recordingContext = RecordingContext(context)
        var restoredOverlayId = -1
        BootReceiver.userPresentOverlayRestorer = { _, alarmId -> restoredOverlayId = alarmId }
        CoreService.isServiceActive = true

        BootReceiver().onReceive(recordingContext, Intent(Intent.ACTION_USER_PRESENT))

        val bootstrap = AlarmBootstrapStorage.find(context, 7)
        assertNotNull(bootstrap)
        assertTrue(bootstrap!!.enabled)
        assertEquals(AlarmScheduler.getAlarms(context).single().scheduleToken, bootstrap.scheduleToken)
        val serviceIntent = recordingContext.foregroundServiceIntents.single {
            it.component?.className == AlarmVibrationService::class.java.name
        }
        assertEquals(AlarmVibrationService::class.java.name, serviceIntent.component?.className)
        assertFalse(serviceIntent.getBooleanExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, false))
        assertEquals(7, serviceIntent.getIntExtra(AlarmVibrationService.EXTRA_ALARM_ID, -1))
        assertEquals(7, AlarmScheduler.getActiveAlarmId(context))
        assertEquals("ce-token", AlarmScheduler.getActiveScheduleToken(context))
        assertEquals(7, restoredOverlayId)
    }

    @Test
    fun `locked application onCreate only performs DPS active recovery without reading CE alarms`() {
        AlarmScheduler.setAlarmActiveFromDirectBoot(context, 7, "application-token")
        val credentialPrefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE)
        credentialPrefs.edit().putString("alarms_json", "not-json").commit()
        val userManager = context.getSystemService(UserManager::class.java)
        var recoveredIntent: Intent? = null
        MyApplication.directBootRecoveryStarter = { intent -> recoveredIntent = Intent(intent) }
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        try {
            MyApplication().apply {
                ReflectionHelpers.callInstanceMethod<Void>(
                    this,
                    "attachBaseContext",
                    ClassParameter.from(Context::class.java, context)
                )
                onCreate()
            }

            val serviceIntent = requireNotNull(recoveredIntent)
            assertTrue(serviceIntent.getBooleanExtra(AlarmVibrationService.EXTRA_DIRECT_BOOT, false))
            assertEquals(7, serviceIntent.getIntExtra(AlarmVibrationService.EXTRA_ALARM_ID, -1))
            assertEquals("application-token", serviceIntent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN))
            assertEquals("not-json", credentialPrefs.getString("alarms_json", null))
            assertEquals(7, AlarmScheduler.getActiveAlarmId(context))
            assertEquals("application-token", AlarmScheduler.getActiveScheduleToken(context))
        } finally {
            Shadows.shadowOf(userManager).setUserUnlocked(true)
        }
    }

    @Test
    fun `cancelAlarm cancels kind-aware pending intent identity`() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = AlarmScheduler.buildPendingIntent(
            context,
            7,
            AlarmScheduler.ACTION_ALARM_TRIGGER,
            AlarmScheduler.AlarmIntentKind.MAIN,
            "cancel-token",
            System.currentTimeMillis() + 60 * 60 * 1000L
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60 * 60 * 1000L, operation)

        AlarmScheduler.cancelAlarm(context, 7)

        assertTrue(Shadows.shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `cancelAlarm keeps alarm armed and reports failure when DPS bootstrap removal fails`() {
        val triggerAt = System.currentTimeMillis() + 60 * 60 * 1000L
        val bootstrap = AlarmBootstrapStorage.Entry(7, triggerAt, true, "cancel-token")
        assertTrue(AlarmBootstrapStorage.replace(context, listOf(bootstrap)))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = AlarmScheduler.buildPendingIntent(
            context,
            7,
            AlarmScheduler.ACTION_ALARM_TRIGGER,
            AlarmScheduler.AlarmIntentKind.MAIN,
            "cancel-token",
            triggerAt
        )
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, operation)

        AlarmBootstrapStorage.commitResultForTest = { false }
        try {
            val cancelled = AlarmScheduler.cancelAlarm(context, 7)

            assertEquals(false, cancelled)
            assertNotNull(AlarmBootstrapStorage.find(context, 7))
            assertEquals(1, Shadows.shadowOf(alarmManager).scheduledAlarms.size)
        } finally {
            AlarmBootstrapStorage.commitResultForTest = null
        }
    }

    @Test
    fun `cancelAll keeps first alarm armed when batch removal fails on second bootstrap`() {
        val triggerAt = System.currentTimeMillis() + 60 * 60 * 1000L
        val entries = listOf(
            AlarmBootstrapStorage.Entry(7, triggerAt, true, "first-token"),
            AlarmBootstrapStorage.Entry(8, triggerAt, true, "second-token")
        )
        assertTrue(AlarmBootstrapStorage.replace(context, entries))
        context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE).edit()
            .putString("alarms_json", org.json.JSONArray().apply {
                entries.forEach { entry ->
                    put(org.json.JSONObject()
                        .put("id", entry.alarmId)
                        .put("hour", 7)
                        .put("minute", 30)
                        .put("enabled", true)
                        .put("days", org.json.JSONArray(BooleanArray(7) { true }.toList()))
                        .put("scheduleToken", entry.scheduleToken)
                    )
                }
            }.toString())
            .putInt("alarm_next_id", 1)
            .commit()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        entries.forEach { entry ->
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                AlarmScheduler.buildPendingIntent(
                    context,
                    entry.alarmId,
                    AlarmScheduler.ACTION_ALARM_TRIGGER,
                    AlarmScheduler.AlarmIntentKind.MAIN,
                    entry.scheduleToken,
                    triggerAt
                )
            )
        }

        AlarmBootstrapStorage.batchRemovalFailureForTest = { 8 in it }
        try {
            assertFalse(AlarmScheduler.cancelAll(context))
            assertNotNull(AlarmBootstrapStorage.find(context, 7))
            assertNotNull(AlarmBootstrapStorage.find(context, 8))
            assertEquals(2, Shadows.shadowOf(alarmManager).scheduledAlarms.size)
        } finally {
            AlarmBootstrapStorage.batchRemovalFailureForTest = null
        }
    }

    private fun directBootIntent(alarmId: Int, token: String, scheduledAt: Long): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_ALARM_TRIGGER
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_TOKEN, token)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT_MILLIS, scheduledAt)
        }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val foregroundServiceIntents = mutableListOf<Intent>()

        override fun startForegroundService(service: Intent): ComponentName? {
            foregroundServiceIntents += Intent(service)
            return service.component
        }

        override fun startService(service: Intent): ComponentName? {
            foregroundServiceIntents += Intent(service)
            return service.component
        }
    }
}
