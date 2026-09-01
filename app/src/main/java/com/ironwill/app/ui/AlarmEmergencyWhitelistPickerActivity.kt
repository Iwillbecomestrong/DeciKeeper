package com.ironwill.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Historical picker launcher, now seamlessly redirects to the optimized AppSelectionActivity
 * with search, app icons, and asynchronous background loading.
 */
class AlarmEmergencyWhitelistPickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, AppSelectionActivity::class.java).apply {
            putExtra(AppSelectionActivity.EXTRA_MODE, AppSelectionActivity.MODE_ALARM_EMERGENCY_WHITELIST)
        }
        startActivity(intent)
        finish()
    }
}

