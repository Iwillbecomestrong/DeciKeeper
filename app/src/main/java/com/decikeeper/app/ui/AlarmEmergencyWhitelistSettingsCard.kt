package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.decikeeper.app.manager.AlarmChallengeEmergencyWhitelist

/** Self-contained card inserted into the global settings page. */
class AlarmEmergencyWhitelistSettingsCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val summary: TextView

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())

        addView(TextView(context).apply {
            text = "闹钟挑战应急白名单"
            textSize = 18f
        })
        addView(TextView(context).apply {
            text = "挑战未完成时，仅允许使用这里选择的最多 2 个应用；默认电话和信息。"
            textSize = 13f
            setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        })
        summary = TextView(context).also { addView(it) }
        addView(Button(context).apply {
            text = "设置白名单"
            gravity = Gravity.CENTER
            setOnClickListener {
                context.startActivity(Intent(context, AlarmEmergencyWhitelistPickerActivity::class.java))
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, (48 * d).toInt()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    fun refresh() {
        val packages = AlarmChallengeEmergencyWhitelist.getPackages(context)
        val labels = packages.map { pkg ->
            try {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
        }
        summary.text = if (labels.isEmpty()) "当前：未配置" else "当前：${labels.joinToString(" · ")}"
    }
}
