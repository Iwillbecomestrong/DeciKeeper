package com.decikeeper.app.manager

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProtectionDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ProtectionDiagnostics.currentTestRunId = null
    }

    @Test
    fun testTestRunIdLifecycle() {
        assertNull(ProtectionDiagnostics.currentTestRunId)
        ProtectionDiagnostics.setTestRunId("RUN_001")
        assertEquals("RUN_001", ProtectionDiagnostics.currentTestRunId)
        ProtectionDiagnostics.endTestRun("RUN_001")
        assertNull(ProtectionDiagnostics.currentTestRunId)
    }

    @Test
    fun testUnbindSequenceGeneration() {
        val seq1 = ProtectionDiagnostics.nextUnbindSequenceId()
        val seq2 = ProtectionDiagnostics.nextUnbindSequenceId()
        assertTrue(seq1.startsWith("A11Y-UNBIND-"))
        assertTrue(seq2.startsWith("A11Y-UNBIND-"))
        assertEquals(seq2, ProtectionDiagnostics.lastUnbindSequenceId)
        assertTrue(ProtectionDiagnostics.lastUnbindUptimeMs > 0)
    }

    @Test
    fun testDumpSnapshotDoesNotThrow() {
        ProtectionDiagnostics.setTestRunId("TEST_SNAPSHOT_RUN")
        // Should execute without crashing regardless of prefs state
        ProtectionDiagnostics.dumpSnapshot(context, trigger = "UNIT_TEST")
        assertNotNull(ProtectionDiagnostics.processInstanceId)
        ProtectionDiagnostics.endTestRun("TEST_SNAPSHOT_RUN")
    }

    @Test
    fun testPermissionManagerA11yLostCounterExposed() {
        val counter = PermissionManager.getA11yLostConsecutiveCount()
        assertTrue(counter >= 0)
    }
}
