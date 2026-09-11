package com.decikeeper.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.decikeeper.app.R
import java.util.Calendar

class TodayAlarmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alarmMinutes: List<Int> = emptyList()

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    fun setAlarms(minutes: List<Int>) {
        alarmMinutes = minutes.sorted()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateDelayed(30_000L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = color(R.attr.zzText)
        val muted = color(R.attr.zzMuted)
        val line = color(R.attr.zzLine)
        val accent = color(R.attr.zzAccent)
        val accentSoft = color(R.attr.zzAccentSoft)
        val surface2 = color(R.attr.zzSurface2)

        // 标签
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textPaint.textSize = sp(12f)
        textPaint.color = text
        canvas.drawText("闹钟点位", dp(14f), dp(25f), textPaint)

        val left = dp(92f)
        val right = width - dp(14f)
        val trackW = (right - left).coerceAtLeast(1f)
        val trackY = dp(18f)
        val trackH = dp(8f)

        // 轨道底色
        paint.style = Paint.Style.FILL
        paint.color = surface2
        canvas.drawRoundRect(RectF(left, trackY, right, trackY + trackH), dp(4f), dp(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = line
        canvas.drawRoundRect(RectF(left, trackY, right, trackY + trackH), dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL

        // 刻度 00, 06, 12, 18, 24
        textPaint.typeface = android.graphics.Typeface.DEFAULT
        textPaint.textSize = sp(9f)
        textPaint.color = muted
        for (h in listOf(0, 6, 12, 18, 24)) {
            val x = left + trackW * (h / 24f)
            val label = h.toString().padStart(2, '0')
            val tw = textPaint.measureText(label)
            canvas.drawText(label, x - tw / 2, dp(38f), textPaint)
        }

        // 当前时间垂直游标
        val now = Calendar.getInstance()
        val currentMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val cursorX = left + trackW * (currentMin.coerceIn(0, 1440) / 1440f)
        paint.color = text
        paint.strokeWidth = dp(1.4f)
        canvas.drawLine(cursorX, dp(14f), cursorX, dp(30f), paint)

        // 绘制闹钟点位
        alarmMinutes.forEach { min ->
            val pointX = left + trackW * (min.coerceIn(0, 1440) / 1440f)
            val pointY = trackY + trackH / 2

            // 外光晕/外环
            paint.style = Paint.Style.FILL
            paint.color = accentSoft
            canvas.drawCircle(pointX, pointY, dp(7f), paint)

            // 核心点
            paint.color = accent
            canvas.drawCircle(pointX, pointY, dp(4.5f), paint)

            // 点位白色内芯
            paint.color = surface2
            canvas.drawCircle(pointX, pointY, dp(1.8f), paint)
        }

        postInvalidateDelayed(30_000L)
    }
}
