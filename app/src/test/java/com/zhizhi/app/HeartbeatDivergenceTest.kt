/**
 * =================================================================================
 * [Context Header]
 * * @file: HeartbeatDivergenceTest.kt
 * * @role: Test
 * * @description: RB-02 Heartbeat Divergence — 验证 MonitorService 心跳输出中 isLocked 与
 *   isFresh 字段，以及分歧时 [HEARTBEAT_DIVERGENT] warning。
 * * @context:
 * - 上游: MonitorService health heartbeat (L394-407)
 * - 下游: 无 — 纯 log 变更，不涉业务逻辑
 * =================================================================================
 */
package com.zhizhi.app

import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.service.MonitorService
import com.zhizhi.app.ui.LockWindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HeartbeatDivergenceTest {

    private lateinit var logCapture: ByteArrayOutputStream
    private lateinit var logFile: File
    private lateinit var service: MonitorService

    // Reflection handles for MonitorService private fields
    private val isRunningField by lazy {
        MonitorService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }
    }
    private val currentLockedPkgField by lazy {
        MonitorService::class.java.getDeclaredField("currentLockedPkg").apply { isAccessible = true }
    }
    private val healthTickField by lazy {
        MonitorService::class.java.getDeclaredField("mHealthTickCount").apply { isAccessible = true }
    }
    private val monitorRunnableField by lazy {
        MonitorService::class.java.getDeclaredField("monitorRunnable").apply { isAccessible = true }
    }

    // Reflection handle for LockWindowManager._isLocked
    private val isLockedAtomicField by lazy {
        LockWindowManager::class.java.getDeclaredField("_isLocked").apply { isAccessible = true }
    }

    @Before
    fun setUp() {
        // Initialize LogExporter so logEvent writes to filesDir
        LogExporter.init(RuntimeEnvironment.getApplication())
        logFile = File(RuntimeEnvironment.getApplication().filesDir, "ironwill_event_log.txt")
        logFile.delete()

        // Capture Log.w output via ShadowLog.stream
        logCapture = ByteArrayOutputStream()
        val shadowLogClass = Class.forName("org.robolectric.shadows.ShadowLog")
        val streamField = shadowLogClass.getDeclaredField("stream")
        streamField.isAccessible = true
        streamField.set(null, PrintStream(logCapture))

        // Create MonitorService (calls onCreate, which initializes LockWindowManager etc.)
        service = Robolectric.buildService(MonitorService::class.java).create().get()

        // Set isRunning = true so the monitorRunnable doesn't early-return
        isRunningField.set(service, true)

        // Reset LockWindowManager locked state
        getIsLockedAtomic().set(false)
    }

    @After
    fun tearDown() {
        val shadowLogClass = Class.forName("org.robolectric.shadows.ShadowLog")
        val streamField = shadowLogClass.getDeclaredField("stream")
        streamField.isAccessible = true
        streamField.set(null, null)
    }

    @Test
    fun emitsFreshTruthTableAndAdjacentWarning() {
        val runnable = monitorRunnableField.get(service) as Runnable
        val isLockedAtomic = getIsLockedAtomic()

        // ── Truth table: 4 cases ──────────────────────────────────────────
        // Case 1: isLocked=true, currentLockedPkg="com.example.app" → isFresh=true  (aligned, no warning)
        clearState()
        isLockedAtomic.set(true)
        currentLockedPkgField.set(service, "com.example.app")
        healthTickField.set(service, 299)

        logCapture.reset()
        logFile.delete()
        runnable.run()

        var heartbeat = readHeartbeatLog()
        assertTrue("Case1 heartbeat must contain isLocked=true", heartbeat.contains("isLocked=true"))
        assertTrue("Case1 heartbeat must contain isFresh=true", heartbeat.contains("isFresh=true"))
        var warning = logCapture.toString()
        assertFalse("Case1 must NOT have [HEARTBEAT_DIVERGENT]", warning.contains("[HEARTBEAT_DIVERGENT]"))

        // Case 2: isLocked=true, currentLockedPkg=null → isFresh=false  (divergent, warning)
        clearState()
        isLockedAtomic.set(true)
        currentLockedPkgField.set(service, null)
        healthTickField.set(service, 299)

        logCapture.reset()
        logFile.delete()
        runnable.run()

        heartbeat = readHeartbeatLog()
        assertTrue("Case2 heartbeat must contain isLocked=true", heartbeat.contains("isLocked=true"))
        assertTrue("Case2 heartbeat must contain isFresh=false", heartbeat.contains("isFresh=false"))
        warning = logCapture.toString()
        assertTrue("Case2 must have [HEARTBEAT_DIVERGENT]", warning.contains("[HEARTBEAT_DIVERGENT]"))

        // Case 3: isLocked=false, currentLockedPkg="com.example.app" → isFresh=false  (divergent, warning)
        clearState()
        isLockedAtomic.set(false)
        currentLockedPkgField.set(service, "com.example.app")
        healthTickField.set(service, 299)

        logCapture.reset()
        logFile.delete()
        runnable.run()

        heartbeat = readHeartbeatLog()
        assertTrue("Case3 heartbeat must contain isLocked=false", heartbeat.contains("isLocked=false"))
        assertTrue("Case3 heartbeat must contain isFresh=false", heartbeat.contains("isFresh=false"))
        warning = logCapture.toString()
        assertTrue("Case3 must have [HEARTBEAT_DIVERGENT]", warning.contains("[HEARTBEAT_DIVERGENT]"))

        // Case 4: isLocked=false, currentLockedPkg=null → isFresh=true  (aligned, no warning)
        clearState()
        isLockedAtomic.set(false)
        currentLockedPkgField.set(service, null)
        healthTickField.set(service, 299)

        logCapture.reset()
        logFile.delete()
        runnable.run()

        heartbeat = readHeartbeatLog()
        assertTrue("Case4 heartbeat must contain isLocked=false", heartbeat.contains("isLocked=false"))
        assertTrue("Case4 heartbeat must contain isFresh=true", heartbeat.contains("isFresh=true"))
        warning = logCapture.toString()
        assertFalse("Case4 must NOT have [HEARTBEAT_DIVERGENT]", warning.contains("[HEARTBEAT_DIVERGENT]"))
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /** Clear ShadowLog stream buffer + heartbeat log file + reset health tick */
    private fun clearState() {
        logCapture.reset()
        logFile.delete()
        // After each run the runnable sets mHealthTickCount = 0, so re-arm
    }

    private fun readHeartbeatLog(): String =
        if (logFile.exists()) logFile.readText() else ""

    private fun getIsLockedAtomic(): AtomicBoolean =
        isLockedAtomicField.get(LockWindowManager) as AtomicBoolean
}
