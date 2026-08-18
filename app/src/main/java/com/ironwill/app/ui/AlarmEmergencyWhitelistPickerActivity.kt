package com.ironwill.app.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ironwill.app.manager.AlarmChallengeEmergencyWhitelist

/** Simple global picker for the two alarm-challenge emergency apps. */
class AlarmEmergencyWhitelistPickerActivity : AppCompatActivity() {
    private val selected = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(AlarmChallengeEmergencyWhitelist.getPackages(this))
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt())
        }
        scroll.addView(column)

        column.addView(TextView(this).apply {
            text = "闹钟挑战应急白名单"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "挑战期间只允许进入这里选择的应用，最多 2 个。默认优先使用系统电话和信息。设置、桌面、浏览器、应用商店和文件管理等高风险入口不会出现在候选中。"
            textSize = 14f
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        })

        val launchable = packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { selected.contains(it.packageName) || packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { AlarmChallengeEmergencyWhitelist.isSelectable(this, it.packageName) }
            .map { app ->
                val label = try { packageManager.getApplicationLabel(app).toString() } catch (_: Exception) { app.packageName }
                label to app.packageName
            }
            .sortedBy { it.first.lowercase() }
            .toList()

        launchable.forEach { (label, pkg) ->
            column.addView(CheckBox(this).apply {
                text = "$label\n$pkg"
                isChecked = selected.contains(pkg)
                setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                setOnCheckedChangeListener { button, checked ->
                    if (checked) {
                        if (selected.size >= AlarmChallengeEmergencyWhitelist.MAX_APPS && !selected.contains(pkg)) {
                            button.isChecked = false
                            Toast.makeText(this@AlarmEmergencyWhitelistPickerActivity, "最多只能选择 2 个应用", Toast.LENGTH_SHORT).show()
                        } else {
                            selected += pkg
                        }
                    } else {
                        selected -= pkg
                    }
                }
            })
        }

        column.addView(Button(this).apply {
            text = "保存"
            gravity = Gravity.CENTER
            setOnClickListener {
                AlarmChallengeEmergencyWhitelist.savePackages(this@AlarmEmergencyWhitelistPickerActivity, selected)
                Toast.makeText(this@AlarmEmergencyWhitelistPickerActivity, "已保存应急白名单", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * density).toInt()).apply {
            topMargin = (16 * density).toInt()
        })

        return scroll
    }
}
