package com.decikeeper.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.decikeeper.app.manager.ProtectionDiagnostics

/**
 * 诊断与自动化测试广播接收器。
 * 允许在命令行通过 adb 触发测试生命周期（BEGIN / SNAPSHOT / END）并带入统一 TEST_RUN_ID。
 */
class ProtectionDiagnosticsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.decikeeper.app.ACTION_TEST_DIAGNOSTICS"
        private const val TAG = "ProtectionDiagReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val actionType = intent.getStringExtra("action") ?: "SNAPSHOT"
        val runId = intent.getStringExtra("runId")
        val trigger = intent.getStringExtra("trigger") ?: "BROADCAST_$actionType"

        Log.i(TAG, "Received test broadcast: actionType=$actionType runId=$runId trigger=$trigger")

        when (actionType.uppercase()) {
            "BEGIN", "START" -> {
                ProtectionDiagnostics.setTestRunId(runId)
                ProtectionDiagnostics.dumpSnapshot(context, trigger = "TEST_BEGIN")
            }
            "END", "STOP" -> {
                ProtectionDiagnostics.dumpSnapshot(context, trigger = "TEST_END")
                ProtectionDiagnostics.endTestRun(runId)
            }
            "SNAPSHOT", "DUMP" -> {
                if (runId != null) {
                    ProtectionDiagnostics.currentTestRunId = runId
                }
                ProtectionDiagnostics.dumpSnapshot(context, trigger = trigger)
            }
            "SET_RUN_ID" -> {
                ProtectionDiagnostics.currentTestRunId = runId
            }
            else -> {
                ProtectionDiagnostics.dumpSnapshot(context, trigger = "UNKNOWN_ACTION_$actionType")
            }
        }
    }
}
