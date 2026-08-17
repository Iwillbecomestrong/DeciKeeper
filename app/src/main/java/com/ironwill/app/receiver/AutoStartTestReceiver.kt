package com.ironwill.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ironwill.app.manager.CreditManager


class AutoStartTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ironwill.app.ACTION_TEST_AUTO_START") {
            Log.d("AutoStartTest", "Received Auto-Start Test Broadcast")
            val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            val pending = prefs.getStringSet("pending_confirm_keys", emptySet()) ?: emptySet()
            if (pending.contains("auto_start_confirmed") || pending.contains("bg_popup_confirmed")) {
                val editor = prefs.edit()
                if (pending.contains("auto_start_confirmed")) {
                    editor.putBoolean("auto_start_confirmed", true)
                    editor.putLong("auto_start_confirmed_at", System.currentTimeMillis())
                }
                if (pending.contains("bg_popup_confirmed")) {
                    editor.putBoolean("bg_popup_confirmed", true)
                    editor.putLong("bg_popup_confirmed_at", System.currentTimeMillis())
                }
                val newPending = pending.toMutableSet()
                newPending.remove("auto_start_confirmed")
                newPending.remove("bg_popup_confirmed")
                editor.putStringSet("pending_confirm_keys", newPending)
                editor.apply()
                Log.d("AutoStartTest", "Auto-start and bg_popup confirmed via broadcast test")
            }
        }
    }
}
