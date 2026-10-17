package com.decikeeper.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.decikeeper.app.R
import com.decikeeper.app.data.formatUsableCountHalfUnits
import com.decikeeper.app.manager.SnackJarPresentation as Snack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/** 自然折行的零食显示；只有点角色的区域接收点击，绝不调用额度或解锁接口。 */
class SnackBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
    }
    private val mascot = ImageView(context).apply { contentDescription = "团团，点一下打个招呼" }
    private val nextJar = NextJarView(context).apply { alpha = 0.65f }
    private val timeText = label(12f)
    private val countText = label(12f)
    private val nextText = label(11f)
    private val parcelText = label(12f)
    private val reactionText = label(12f)
    private val parcelIcon = ImageView(context).apply { setImageResource(R.drawable.obj_parcel) }
    private val parcelRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val timeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzAccent))
        progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, R.attr.zzLine))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val cookies = CookieRow(context)
    private val flightCookie = ContextCompat.getDrawable(context, R.drawable.obj_cookie)?.mutate()
    private val taps = Snack.Taps()
    private var snapshot: Snack.Snapshot? = null
    internal val needsSnapshot: Boolean get() = snapshot == null
    internal val snapshotIdentity: String? get() = snapshot?.identity
    private var animator: ValueAnimator? = null
    private var flight = -1f
    private var activeEffect = Snack.Effect.NONE
    private val resetReaction = Runnable {
        activeEffect = Snack.Effect.NONE
        reactionText.visibility = GONE
        showDefaultPose()
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = ContextCompat.getDrawable(context, R.drawable.bg_zz_card)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(mascot, LayoutParams(dp(64), dp(64)))
        val bars = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        bars.addView(timeText)
        bars.addView(timeBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(12)))
        bars.addView(countText)
        bars.addView(cookies, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        top.addView(bars, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(nextJar, LayoutParams(dp(40), dp(48)))
        addView(top)
        addView(nextText)
        parcelRow.addView(parcelIcon, LayoutParams(dp(24), dp(28)))
        parcelRow.addView(parcelText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(parcelRow)
        addView(reactionText)
        reactionText.visibility = GONE
        visibility = GONE
        mascot.setOnClickListener {
            val reaction = taps.accept(SystemClock.elapsedRealtime()) ?: return@setOnClickListener
            val surprise = reaction == Snack.Reaction.SURPRISE
            setPose(if (surprise) R.drawable.mascot_eat else defaultPose())
            reactionText.text = if (surprise) "被你发现啦，团团也想吃一口" else listOf("别戳啦", "再给我一块", "我陪你等快递", "罐子我帮你看着").random()
            reactionText.visibility = VISIBLE
            if (ValueAnimator.areAnimatorsEnabled()) {
                mascot.animate().cancel()
                mascot.scaleX = 1.12f; mascot.scaleY = 0.82f
                mascot.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }
            removeCallbacks(resetReaction); postDelayed(resetReaction, 2200)
        }
    }

    internal fun render(value: Snack.Snapshot?, now: Long = System.currentTimeMillis()) {
        if (value == null) {
            snapshot = null; visibility = GONE
            stopEffects()
            return
        }
        val effect = Snack.transition(snapshot, value, now)
        snapshot = value
        visibility = VISIBLE
        val quota = value.quota
        timeText.text = "可用时长 ${minutes(quota.remainingMinutes)} 分钟"
        countText.text = "可用次数 ${formatUsableCountHalfUnits(quota.remainingHalfUnits.coerceAtLeast(0))} 次" +
            if (maxOf(quota.remainingHalfUnits, quota.totalHalfUnits) > 24) "（图示前12块）" else ""
        timeBar.progress = (Snack.timeFraction(quota) * 1000).toInt()
        cookies.bind(quota.remainingHalfUnits, quota.totalHalfUnits)
        val next = value.nextQuota
        nextJar.visibility = if (next == null) GONE else VISIBLE
        nextJar.bind(next)
        nextText.visibility = if (next == null) GONE else VISIBLE
        nextText.text = if (next == null) "" else "下一罐 · ${value.nextName}：${minutes(next.remainingMinutes)} 分钟 / ${formatUsableCountHalfUnits(next.remainingHalfUnits)} 次"
        nextJar.contentDescription = nextText.text
        val parcel = value.parcels.firstOrNull()
        parcelRow.visibility = if (parcel == null) GONE else VISIBLE
        parcelText.text = parcel?.let {
            val left = ((it.effectiveMillis - now + 59_999) / 60_000).coerceAtLeast(0)
            "${it.segmentName}的新罐子 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.effectiveMillis))} 送到 · " +
                if (left > 0) "还剩 ${left / 60}时${left % 60}分" else "等待规则生效"
        } ?: ""
        contentDescription = "团团的零食罐。${timeText.text}，${countText.text}。${nextText.text}。${parcelText.text}"
        if (effect != Snack.Effect.NONE) playEffect(effect)
        else if (activeEffect == Snack.Effect.NONE && reactionText.visibility != VISIBLE) showDefaultPose()
    }

    /** 每秒只替换已有快照的余额和倒计时，不扫描规则与 pending。 */
    internal fun updateBalance(minutes: Double, halfUnits: Int, now: Long) {
        val old = snapshot ?: return
        render(old.copy(quota = old.quota.copy(remainingMinutes = minutes, remainingHalfUnits = halfUnits)), now)
    }

    private fun minutes(value: Double) = if (value.isFinite()) String.format(Locale.getDefault(), "%.1f", value.coerceAtLeast(0.0)) else "0.0"
    private fun defaultPose() = if ((snapshot?.quota?.remainingHalfUnits ?: 0) <= 0 || (snapshot?.quota?.remainingMinutes ?: 0.0) <= 0) R.drawable.mascot_jar_empty else R.drawable.mascot_jar_full
    private fun setPose(resource: Int) {
        if (mascot.tag != resource) { mascot.setImageResource(resource); mascot.tag = resource }
    }
    private fun showDefaultPose() { setPose(defaultPose()) }

    private fun playEffect(effect: Snack.Effect) {
        activeEffect = effect
        setPose(when (effect) {
            Snack.Effect.BORROW -> R.drawable.mascot_reach
            Snack.Effect.DELIVERED -> R.drawable.mascot_parcel_open
            Snack.Effect.RETURNED -> R.drawable.mascot_peek
            else -> R.drawable.mascot_happy
        })
        reactionText.text = when (effect) {
            Snack.Effect.BORROW -> "向下一罐借了一点，余量以账本为准"
            Snack.Effect.DELIVERED -> "新罐子送到啦！"
            Snack.Effect.RETURNED -> "包裹退回啦，原来的罐子继续用"
            Snack.Effect.STRICTER -> "换个小罐子，团团帮你守住"
            else -> ""
        }
        reactionText.visibility = VISIBLE
        if (effect == Snack.Effect.BORROW && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                addUpdateListener { flight = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { flight = -1f; invalidate() }
                })
                start()
            }
        }
        removeCallbacks(resetReaction); postDelayed(resetReaction, 2400)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (flight < 0f) return
        val icon = flightCookie ?: return
        val x = (width - dp(36)) * (1 - flight) + dp(34) * flight
        val y = dp(32) - sin(flight * Math.PI).toFloat() * dp(20)
        icon.setBounds(x.toInt() - dp(9), y.toInt() - dp(9), x.toInt() + dp(9), y.toInt() + dp(9))
        icon.draw(canvas)
    }

    private fun stopEffects() {
        removeCallbacks(resetReaction)
        animator?.cancel(); animator = null; flight = -1f
        mascot.animate().cancel(); mascot.scaleX = 1f; mascot.scaleY = 1f
        activeEffect = Snack.Effect.NONE; reactionText.visibility = GONE
    }
    override fun onDetachedFromWindow() { stopEffects(); snapshot = null; super.onDetachedFromWindow() }

    /** 下一罐按真实剩余比例填充，数字仍保留完整额度，明日配置不混入今日扣费。 */
    private class NextJarView(context: Context) : View(context) {
        private val jar = ContextCompat.getDrawable(context, R.drawable.obj_jar)?.mutate()
        private val cookie = ContextCompat.getDrawable(context, R.drawable.obj_cookie)?.mutate()
        private var fraction = 0f
        fun bind(quota: Snack.Quota?) {
            fraction = if (quota == null) 0f else if (quota.totalHalfUnits > 0)
                (quota.remainingHalfUnits.toFloat() / quota.totalHalfUnits).coerceIn(0f, 1f)
            else Snack.timeFraction(quota)
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val jarImage = jar ?: return
            val fit = minOf(width.toFloat() / jarImage.intrinsicWidth.coerceAtLeast(1),
                height.toFloat() / jarImage.intrinsicHeight.coerceAtLeast(1))
            val jarWidth = jarImage.intrinsicWidth.coerceAtLeast(1) * fit
            val jarHeight = jarImage.intrinsicHeight.coerceAtLeast(1) * fit
            val left = (width - jarWidth) / 2
            val top = (height - jarHeight) / 2
            jarImage.setBounds(left.toInt(), top.toInt(), (left + jarWidth).toInt(), (top + jarHeight).toInt())
            jarImage.draw(canvas)
            val drawable = cookie ?: return
            val size = jarWidth * 0.25f
            repeat(6) { index ->
                val fill = (fraction * 6 - index).coerceIn(0f, 1f)
                if (fill <= 0f) return@repeat
                val x = left + jarWidth * 0.23f + (index % 2) * size
                val y = top + jarHeight * 0.75f - (index / 2) * size * 0.8f
                val checkpoint = canvas.save()
                canvas.clipRect(x, y, x + size * fill, y + size)
                drawable.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
                drawable.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        }
    }

    private class CookieRow(context: Context) : View(context) {
        private var fills = listOf(0f)
        private val icon = ContextCompat.getDrawable(context, R.drawable.obj_cookie)?.mutate()
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun bind(remaining: Int, total: Int) {
            val next = Snack.cookieFills(remaining, total)
            if (next != fills) { fills = next; invalidate() }
        }
        override fun onDraw(canvas: Canvas) {
            val drawable = icon ?: return
            val cell = minOf(height.toFloat(), width.toFloat() / fills.size)
            fills.forEachIndexed { index, fill ->
                val left = index * cell
                drawable.setBounds(left.toInt(), 0, (left + cell - 2).toInt(), cell.toInt())
                drawable.alpha = 45; drawable.draw(canvas)
                val checkpoint = canvas.save()
                canvas.clipRect(left, 0f, left + (cell - 2) * fill, cell)
                drawable.alpha = 255; drawable.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}
