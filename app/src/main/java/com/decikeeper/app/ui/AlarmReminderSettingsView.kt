package com.decikeeper.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.decikeeper.app.R
import com.decikeeper.app.manager.AlarmRingtoneManager
import com.decikeeper.app.manager.AlarmScheduler

/**
 * 提醒设置卡片：提醒方式（震动+铃声 / 仅震动 / 仅铃声）、提示音、渐进或固定强度、震动与声音强度。
 *
 * 新增 / 编辑闹钟里的第一关、第二关独立提醒共用这一个控件，样式统一走应用主题色与圆角资源，
 * 不再各处用系统默认的按钮、单选钮和滑块手拼。
 */
internal class AlarmReminderSettingsView(
    context: Context,
    private val showRingtone: Boolean
) : LinearLayout(context) {

    data class Config(
        val alarmMode: AlarmScheduler.AlarmMode,
        val vibrationMode: AlarmScheduler.VibrationMode,
        val vibrationIntensity: Int,
        val ringVolume: Int,
        val ringtoneUri: String? = null,
        /** 渐进模式的「最大声音」（1-10，10 = 不限制）；固定模式下不使用，但两个值始终一并保存。 */
        val progressiveMaxRingVolume: Int = 10,
        /** 渐进模式的「最大震动」（1-10，10 = 不限制）；固定模式下不使用，但两个值始终一并保存。 */
        val progressiveMaxVibrationIntensity: Int = 10
    )

    /** 当前模式下哪些子控件需要显示；独立成纯函数便于 JVM 测试。 */
    data class Visibility(
        val ringtone: Boolean,
        val progressiveHint: Boolean,
        val vibrationSlider: Boolean,
        val volumeSlider: Boolean
    )

    var onPickRingtone: (() -> Unit)? = null

    /** 声音滑块松手时回调当前滑块所代表的档位（1-10，渐进=最大声音），用于试听。 */
    var onPreviewVolume: ((level: Int) -> Unit)? = null

    /** 震动滑块松手时回调当前滑块所代表的档位（1-10，渐进=最大震动），用于试震。 */
    var onPreviewVibration: ((level: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var alarmMode = AlarmScheduler.AlarmMode.VIBRATE_AND_RING
    private var vibrationMode = AlarmScheduler.VibrationMode.PROGRESSIVE
    private var ringtoneUri: String? = null

    // 震动、声音滑块在两种强度模式间各复用同一行（渐进=最大值，固定=固定值），两个模式各自的值分开存放。
    private var fixedRingProgress = 6
    private var maxRingProgress = 9
    private var fixedVibrationProgress = 6
    private var maxVibrationProgress = 9

    private val modeControl: SegmentedControl
    private val strengthControl: SegmentedControl
    private val ringtoneRow: LinearLayout
    private val ringtoneTitle: TextView
    private val progressiveHint: TextView
    private val vibrationRow: LinearLayout
    private val volumeRow: LinearLayout
    private val vibrationSeek: SeekBar
    private val volumeSeek: SeekBar
    private val volumeLabel: TextView
    private val vibrationLabel: TextView
    private val vibrationValue: TextView
    private val volumeValue: TextView

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_zz_card_soft)
        setPadding(dp(14), dp(12), dp(14), dp(14))

        addView(sectionLabel("提醒方式"))
        modeControl = SegmentedControl(listOf("震动+铃声", "仅震动", "仅铃声")) { index ->
            alarmMode = MODES[index]
            refreshVisibility()
        }
        addView(modeControl, params(top = 6))

        ringtoneTitle = TextView(context).apply {
            textSize = 14f
            setTextColor(color(R.attr.zzText))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        ringtoneRow = buildRingtoneRow()
        addView(ringtoneRow, params(top = 8))

        addView(sectionLabel("提醒强度").also { it.setPadding(0, dp(12), 0, 0) })
        strengthControl = SegmentedControl(listOf("渐进增强", "固定强度")) { index ->
            commitSeeks()
            vibrationMode = STRENGTHS[index]
            loadSeeks()
            refreshVisibility()
        }
        addView(strengthControl, params(top = 6))

        progressiveHint = TextView(context).apply {
            text = "从最低强度开始，每 30 秒提高 1 级，直到最高强度"
            textSize = 12f
            setTextColor(color(R.attr.zzMuted))
        }
        addView(progressiveHint, params(top = 8))

        vibrationValue = valueLabel()
        vibrationSeek = themedSeekBar(vibrationValue, previewOnRelease = true, previewIsVibration = true)
        vibrationLabel = rowLabel("震动")
        vibrationRow = sliderRow(vibrationLabel, vibrationSeek, vibrationValue)
        addView(vibrationRow, params(top = 8))

        volumeValue = valueLabel()
        volumeSeek = themedSeekBar(volumeValue, previewOnRelease = true)
        volumeLabel = rowLabel("声音")
        volumeRow = sliderRow(volumeLabel, volumeSeek, volumeValue)
        addView(volumeRow, params(top = 4))

        setConfig(
            Config(
                alarmMode = alarmMode,
                vibrationMode = vibrationMode,
                vibrationIntensity = 7,
                ringVolume = 7
            )
        )
    }

    fun setConfig(config: Config) {
        alarmMode = config.alarmMode
        vibrationMode = config.vibrationMode
        ringtoneUri = config.ringtoneUri
        modeControl.select(MODES.indexOf(alarmMode).coerceAtLeast(0))
        strengthControl.select(STRENGTHS.indexOf(vibrationMode).coerceAtLeast(0))
        fixedVibrationProgress = (config.vibrationIntensity - 1).coerceIn(0, 9)
        maxVibrationProgress = (config.progressiveMaxVibrationIntensity - 1).coerceIn(0, 9)
        fixedRingProgress = (config.ringVolume - 1).coerceIn(0, 9)
        maxRingProgress = (config.progressiveMaxRingVolume - 1).coerceIn(0, 9)
        loadSeeks()
        renderRingtoneTitle()
        refreshVisibility()
    }

    /** 固定值与渐进最大值始终一并输出，互不覆盖；渐进时爬升由策略负责，固定值只是保留给切回固定模式。 */
    fun getConfig(): Config {
        commitSeeks()
        return Config(
            alarmMode = alarmMode,
            vibrationMode = vibrationMode,
            vibrationIntensity = fixedVibrationProgress + 1,
            ringVolume = fixedRingProgress + 1,
            ringtoneUri = ringtoneUri,
            progressiveMaxRingVolume = maxRingProgress + 1,
            progressiveMaxVibrationIntensity = maxVibrationProgress + 1
        )
    }

    /** 把两个滑块当前位置存回当前模式对应的那个值。 */
    private fun commitSeeks() {
        if (vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE) {
            maxVibrationProgress = vibrationSeek.progress
            maxRingProgress = volumeSeek.progress
        } else {
            fixedVibrationProgress = vibrationSeek.progress
            fixedRingProgress = volumeSeek.progress
        }
    }

    /** 按当前模式把对应的值和标签装到同一对滑块上。 */
    private fun loadSeeks() {
        val progressive = vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE
        vibrationLabel.text = if (progressive) "最大震动" else "震动"
        vibrationSeek.progress = if (progressive) maxVibrationProgress else fixedVibrationProgress
        vibrationValue.text = valueText(vibrationSeek.progress)
        volumeLabel.text = if (progressive) "最大声音" else "声音"
        volumeSeek.progress = if (progressive) maxRingProgress else fixedRingProgress
        volumeValue.text = valueText(volumeSeek.progress)
    }

    fun setRingtone(uri: String?) {
        ringtoneUri = uri
        renderRingtoneTitle()
    }

    private fun renderRingtoneTitle() {
        ringtoneTitle.text = AlarmRingtoneManager.getRingtoneTitle(context, ringtoneUri)
    }

    private fun refreshVisibility() {
        val v = resolveVisibility(alarmMode, vibrationMode, showRingtone)
        // 规格 2.3：被当前模式隐藏的控件直接收起（GONE），下方内容自动上移，不留空白；弹窗在 ScrollView 内，高度变化无碍。
        ringtoneRow.visibility = if (v.ringtone) VISIBLE else GONE
        progressiveHint.visibility = if (v.progressiveHint) VISIBLE else GONE
        vibrationRow.visibility = if (v.vibrationSlider) VISIBLE else GONE
        volumeRow.visibility = if (v.volumeSlider) VISIBLE else GONE
    }

    private fun buildRingtoneRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_zz_option_btn)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minimumHeight = dp(52)
            isClickable = true
            isFocusable = true
            setOnClickListener { onPickRingtone?.invoke() }
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = "提示音"
            textSize = 11f
            setTextColor(color(R.attr.zzMuted))
        })
        texts.addView(ringtoneTitle)
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            setTextColor(color(R.attr.zzSubtle))
        })
        return row
    }

    /** 两行滑块的标签等宽（容得下「最大声音」），保证震动与声音滑块左右对齐。 */
    private fun rowLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.attr.zzMuted))
        minWidth = dp(60)
    }

    private fun sliderRow(label: TextView, seek: SeekBar, value: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label)
            addView(seek, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }

    private fun valueLabel() = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.attr.zzAccentStrong))
        minWidth = dp(44)
        gravity = Gravity.END
    }

    private fun themedSeekBar(
        valueView: TextView,
        previewOnRelease: Boolean = false,
        previewIsVibration: Boolean = false
    ) = SeekBar(context).apply {
        max = 9
        progressTintList = ColorStateList.valueOf(color(R.attr.zzAccent))
        progressBackgroundTintList = ColorStateList.valueOf(color(R.attr.zzLineStrong))
        thumbTintList = ColorStateList.valueOf(color(R.attr.zzAccent))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueView.text = valueText(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!previewOnRelease || sb == null) return
                val level = sb.progress + 1
                if (previewIsVibration) onPreviewVibration?.invoke(level) else onPreviewVolume?.invoke(level)
            }
        })
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.attr.zzText))
    }

    private fun valueText(progress: Int) = "${progress + 1} / 10"

    private fun params(top: Int = 0) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun color(attr: Int) = ThemeManager.getColorAttr(context, attr)

    private fun dp(n: Int) = (n * density).toInt()

    /** 分段选择：选中项用主题强调色填充，未选中项用描边，替代系统单选钮。 */
    private inner class SegmentedControl(
        labels: List<String>,
        private val onSelect: (Int) -> Unit
    ) : LinearLayout(context) {
        private val items = labels.mapIndexed { index, label ->
            TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    select(index)
                    onSelect(index)
                }
            }
        }

        init {
            orientation = HORIZONTAL
            items.forEachIndexed { index, view ->
                addView(view, LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
        }

        fun select(index: Int) {
            items.forEachIndexed { i, view ->
                val selected = i == index
                view.setBackgroundResource(
                    if (selected) R.drawable.bg_zz_option_btn_selected else R.drawable.bg_zz_option_btn
                )
                view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else color(R.attr.zzText))
                view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }

    companion object {
        private val MODES = listOf(
            AlarmScheduler.AlarmMode.VIBRATE_AND_RING,
            AlarmScheduler.AlarmMode.VIBRATE_ONLY,
            AlarmScheduler.AlarmMode.RING_ONLY
        )
        private val STRENGTHS = listOf(
            AlarmScheduler.VibrationMode.PROGRESSIVE,
            AlarmScheduler.VibrationMode.FIXED
        )

        /**
         * 震动滑块在含震动时显示，声音滑块在含铃声时显示；渐进模式下它们分别是「最大震动」「最大声音」，
         * 固定模式下是「震动」「声音」。渐进另外显示一行说明。
         */
        fun resolveVisibility(
            alarmMode: AlarmScheduler.AlarmMode,
            vibrationMode: AlarmScheduler.VibrationMode,
            showRingtone: Boolean
        ): Visibility {
            val progressive = vibrationMode == AlarmScheduler.VibrationMode.PROGRESSIVE
            val hasRing = alarmMode != AlarmScheduler.AlarmMode.VIBRATE_ONLY
            val hasVibration = alarmMode != AlarmScheduler.AlarmMode.RING_ONLY
            return Visibility(
                ringtone = showRingtone && hasRing,
                progressiveHint = progressive,
                vibrationSlider = hasVibration,
                volumeSlider = hasRing
            )
        }
    }
}
