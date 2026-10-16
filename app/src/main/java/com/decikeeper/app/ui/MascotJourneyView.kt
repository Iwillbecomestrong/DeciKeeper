package com.decikeeper.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.decikeeper.app.R
import com.decikeeper.app.manager.MascotJourney
import com.decikeeper.app.manager.MascotJourneyProvider
import kotlin.math.abs
import kotlin.math.sin

/**
 * 团团赶路：勿扰时段内，一条进度条上团团随时间往终点走，到了就欢呼。
 *
 * 自己按需读取当天受限时段（每 30 秒一次），没有可用时段时自动隐藏，
 * 因此首页和锁屏页只需要在布局里放一个即可，不用额外接线。
 *
 * 常驻起伏动画只在 mascotBob 为 true 时出现；锁屏页是悬浮窗，保持静止，
 * 只随每次刷新移动一小段。到达终点时的欢呼是一次性的短动画。
 */
class MascotJourneyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flagPath = Path()
    private val trackRect = RectF()
    // 同一个角色的三种姿势：赶路 / 打盹 / 欢呼（与宣传 MV 里的团团是同一个形象）
    private val walkPose = ContextCompat.getDrawable(context, R.drawable.mascot_walk)?.mutate()
    private val sleepPose = ContextCompat.getDrawable(context, R.drawable.mascot_sleep)?.mutate()
    private val happyPose = ContextCompat.getDrawable(context, R.drawable.mascot_happy)?.mutate()

    private var state: MascotJourney.State = MascotJourney.State.None
    private var bobOffset = 0f        // 0..1，常驻起伏
    private var hop = 0f              // 0..1，到达欢呼
    private val bobEnabled: Boolean

    private var initialized = false
    private var bobAnimator: ValueAnimator? = null
    private var hopAnimator: ValueAnimator? = null
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, REFRESH_MS)
        }
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MascotJourneyView)
        bobEnabled = a.getBoolean(R.styleable.MascotJourneyView_mascotBob, true)
        a.recycle()
        if (isInEditMode) state = MascotJourney.State.Walking(0.4f, 108)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun color(attr: Int) = ThemeManager.getColorAttr(context, attr)

    /** 重新计算状态；供布局内的自刷新与外部强制刷新使用。 */
    fun refresh() {
        if (isInEditMode) return
        applyState(MascotJourneyProvider.current(context))
    }

    /** 直接指定状态（预览与测试用）。 */
    internal fun showState(newState: MascotJourney.State) = applyState(newState)

    private fun applyState(newState: MascotJourney.State) {
        val old = state
        if (newState == old && initialized) return
        initialized = true
        state = newState
        visibility = if (newState != MascotJourney.State.None) VISIBLE else GONE
        contentDescription = MascotJourney.caption(newState).takeIf { it.isNotEmpty() }
        if (old is MascotJourney.State.Walking && newState is MascotJourney.State.Arrived) startHop()
        updateBob()
        invalidate()
    }

    private fun updateBob() {
        val shouldBob = bobEnabled && isShown && state is MascotJourney.State.Walking &&
            ValueAnimator.areAnimatorsEnabled()
        if (shouldBob) {
            if (bobAnimator == null) {
                bobAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { bobOffset = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            bobAnimator?.cancel()
            bobAnimator = null
            bobOffset = 0f
        }
    }

    private fun startHop() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        hopAnimator?.cancel()
        hopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            addUpdateListener { hop = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
        removeCallbacks(refreshRunnable)
        postDelayed(refreshRunnable, REFRESH_MS)
        updateBob()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshRunnable)
        bobAnimator?.cancel(); bobAnimator = null
        hopAnimator?.cancel(); hopAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateBob()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (visibility == GONE) 0 else dp(HEIGHT_DP).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state
        if (s == MascotJourney.State.None) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padH = dp(14f)
        val trackY = h - dp(14f)
        val trackH = dp(8f)
        val trackLeft = padH
        val trackRight = w - padH

        val accent = color(R.attr.zzAccent)
        val accentStrong = color(R.attr.zzAccentStrong)
        val line = color(R.attr.zzLine)
        val text = color(R.attr.zzText)
        val muted = color(R.attr.zzMuted)

        // 说明文字
        textPaint.color = if (s is MascotJourney.State.Waiting) muted else text
        textPaint.textSize = sp(13f)
        canvas.drawText(MascotJourney.caption(s), padH, dp(18f), textPaint)

        // 轨道与已走部分
        val progress = when (s) {
            is MascotJourney.State.Walking -> s.progress
            is MascotJourney.State.Arrived -> 1f
            else -> 0f
        }
        paint.style = Paint.Style.FILL
        paint.color = line
        trackRect.set(trackLeft, trackY - trackH / 2, trackRight, trackY + trackH / 2)
        canvas.drawRoundRect(trackRect, trackH / 2, trackH / 2, paint)
        val mascotX = trackLeft + progress * (trackRight - trackLeft)
        if (progress > 0f) {
            paint.color = accent
            trackRect.set(trackLeft, trackY - trackH / 2, mascotX.coerceAtLeast(trackLeft + trackH), trackY + trackH / 2)
            canvas.drawRoundRect(trackRect, trackH / 2, trackH / 2, paint)
        }

        // 终点小旗
        val poleH = dp(16f)
        paint.color = accentStrong
        paint.strokeWidth = dp(1.5f)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(trackRight, trackY, trackRight, trackY - poleH, paint)
        paint.style = Paint.Style.FILL
        flagPath.reset()
        flagPath.moveTo(trackRight, trackY - poleH)
        flagPath.lineTo(trackRight - dp(9f), trackY - poleH + dp(4f))
        flagPath.lineTo(trackRight, trackY - poleH + dp(8f))
        flagPath.close()
        canvas.drawPath(flagPath, paint)

        // 团团：按状态换姿势，并缩放进同一个限制框里，保证三种姿势看起来大小相当
        val drawable = when (s) {
            is MascotJourney.State.Waiting -> sleepPose
            is MascotJourney.State.Arrived -> happyPose
            else -> walkPose
        } ?: return
        val maxW = dp(MASCOT_MAX_WIDTH_DP)
        val maxH = dp(MASCOT_MAX_HEIGHT_DP)
        val iw = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val ih = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val fit = minOf(maxW / iw, maxH / ih)
        val mw = iw * fit
        val mh = ih * fit
        val bob = -bobOffset * dp(2f)
        val hopLift = abs(sin(hop * Math.PI * 3)).toFloat() * (1f - hop) * dp(14f)
        val bottom = trackY - trackH / 2 - dp(1f) + bob - hopLift
        val top = bottom - mh
        val cx = mascotX.coerceIn(trackLeft + mw / 2, trackRight - mw / 2 - dp(4f))
        drawable.setBounds((cx - mw / 2).toInt(), top.toInt(), (cx + mw / 2).toInt(), bottom.toInt())
        drawable.draw(canvas)

        // 打盹的 zZ
        if (s is MascotJourney.State.Waiting) {
            textPaint.color = muted
            textPaint.textSize = sp(12f)
            canvas.drawText("zZ", cx + mw / 2 + dp(2f), top + dp(6f), textPaint)
        }
        // 欢呼的小星星
        if (s is MascotJourney.State.Arrived && hop > 0f && hop < 1f) {
            textPaint.color = accentStrong
            textPaint.textSize = sp(12f)
            canvas.drawText("✦", cx - mw / 2 - dp(10f), top + dp(10f), textPaint)
            canvas.drawText("✦", cx + mw / 2 - dp(2f), top - dp(2f), textPaint)
        }
    }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val HEIGHT_DP = 76f
        const val MASCOT_MAX_WIDTH_DP = 52f
        const val MASCOT_MAX_HEIGHT_DP = 40f
    }
}
