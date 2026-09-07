package com.zhizhi.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.zhizhi.app.R
import com.zhizhi.app.manager.AppWindowManager
import com.zhizhi.app.manager.TimeRuleManager
import java.util.Calendar

class TodayRulesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun color(attr: Int): Int { val tv = TypedValue(); context.theme.resolveAttribute(attr, tv, true); return tv.data }
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateDelayed(30_000L) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = color(R.attr.zzText); val muted = color(R.attr.zzMuted); val line = color(R.attr.zzLine); val accent = color(R.attr.zzAccent); val alt = color(R.attr.zzAlt); val surface2 = color(R.attr.zzSurface2)
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); textPaint.textSize = sp(12f); textPaint.color = text
        canvas.drawText("勿扰时段", dp(14f), dp(30f), textPaint); canvas.drawText("应用时间窗", dp(14f), dp(88f), textPaint)
        val left = dp(92f); val right = width - dp(14f); val trackW = (right - left).coerceAtLeast(1f)
        drawTrack(canvas, left, right, dp(23f), surface2, line); drawTrack(canvas, left, right, dp(81f), surface2, line)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        runCatching { TimeRuleManager.getTimeSegments(context) }.getOrDefault(emptyList()).filter { it.isEnabled && it.isActiveToday(today) }.forEach { seg -> drawRange(canvas, left, trackW, dp(23f), seg.startHour * 60 + seg.startMinute, seg.endHour * 60 + seg.endMinute, accent) }
        runCatching { AppWindowManager.getRules(context) }.getOrDefault(mutableListOf()).filter { it.isEnabled && (it.daysOfWeek.isEmpty() || it.daysOfWeek.contains(today)) }.forEach { rule -> drawRange(canvas, left, trackW, dp(81f), rule.startTime, rule.endTime, alt) }
        textPaint.typeface = android.graphics.Typeface.DEFAULT; textPaint.textSize = sp(9f); textPaint.color = muted
        for (h in listOf(0, 6, 12, 18, 24)) { val x = left + trackW * (h / 24f); val label = h.toString().padStart(2, '0'); val tw = textPaint.measureText(label); canvas.drawText(label, x - tw / 2, dp(48f), textPaint); canvas.drawText(label, x - tw / 2, dp(106f), textPaint) }
        val now = Calendar.getInstance(); val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE); val x = left + trackW * (minute / 1440f)
        paint.color = text; paint.strokeWidth = dp(1.4f); canvas.drawLine(x, dp(18f), x, dp(36f), paint); canvas.drawLine(x, dp(76f), x, dp(94f), paint)
        textPaint.textSize = sp(10f); textPaint.color = muted; canvas.drawText("当前 ${String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))}", dp(14f), dp(132f), textPaint)
        postInvalidateDelayed(30_000L)
    }
    private fun drawTrack(canvas: Canvas, left: Float, right: Float, y: Float, fill: Int, stroke: Int) { paint.style = Paint.Style.FILL; paint.color = fill; canvas.drawRoundRect(RectF(left, y, right, y + dp(10f)), dp(5f), dp(5f), paint); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(0.8f); paint.color = stroke; canvas.drawRoundRect(RectF(left, y, right, y + dp(10f)), dp(5f), dp(5f), paint); paint.style = Paint.Style.FILL }
    private fun drawRange(canvas: Canvas, left: Float, width: Float, y: Float, start: Int, end: Int, c: Int) { paint.color = c; fun one(s: Int, e: Int) { val l = left + width * (s.coerceIn(0, 1440) / 1440f); val r = left + width * (e.coerceIn(0, 1440) / 1440f); if (r > l) canvas.drawRoundRect(RectF(l, y, r, y + dp(10f)), dp(5f), dp(5f), paint) }; if (end == start) one(0, 1440) else if (end > start) one(start, end) else { one(start, 1440); one(0, end) } }
}
