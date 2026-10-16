package com.decikeeper.app.ui.questionbank

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.util.Log
import com.decikeeper.app.MyApplication
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 公式排版引擎（JLaTeXMath）的懒初始化：首次显示公式时才加载字体，失败则回退轻量显示。 */
internal object MathEngine {
    private const val TAG = "MathEngine"

    @Volatile
    private var state: Boolean? = null

    fun isReady(): Boolean {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            state = try {
                JLatexMathAndroid.init(MyApplication.instance)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "formula engine init failed, falling back to lightweight text", t)
                false
            }
            return state == true
        }
    }
}

/**
 * 把一个 LaTeX 公式画进文字行里。颜色与字号取自绘制时的 Paint，跟随所在 TextView 的样式；
 * 公式过宽时整体缩小以免被截断；排版失败时退回显示原始公式文字。
 */
internal class MathFormulaSpan(private val latex: String) : ReplacementSpan() {

    private var drawable: JLatexMathDrawable? = null
    private var builtSize = -1f
    private var builtColor = 0
    private var failed = false
    private var scale = 1f
    private var baselineRatio = 0.75f

    private fun ensure(paint: Paint): JLatexMathDrawable? {
        if (failed) return null
        if (drawable != null && builtSize == paint.textSize && builtColor == paint.color) return drawable
        if (!MathEngine.isReady()) { failed = true; return null }
        return try {
            val d = JLatexMathDrawable.builder(latex)
                .textSize(paint.textSize)
                .color(paint.color)
                .padding(0)
                .align(JLatexMathDrawable.ALIGN_LEFT)
                .build()
            drawable = d
            builtSize = paint.textSize
            builtColor = paint.color
            baselineRatio = d.icon().baseLine.coerceIn(0.05f, 0.98f)
            d
        } catch (t: Throwable) {
            Log.w("MathFormulaSpan", "cannot typeset: $latex", t)
            failed = true
            null
        }
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val d = ensure(paint) ?: return ceil(paint.measureText(latex)).toInt()
        val w = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val h = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val maxWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels * MAX_WIDTH_RATIO
        scale = min(1f, maxWidth / w)
        if (fm != null) {
            val ascent = ceil(h * baselineRatio * scale).toInt()
            val depth = ceil(h * (1f - baselineRatio) * scale).toInt()
            fm.ascent = min(fm.ascent, -ascent)
            fm.descent = max(fm.descent, depth)
            fm.top = min(fm.top, fm.ascent)
            fm.bottom = max(fm.bottom, fm.descent)
        }
        return ceil(w * scale).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val d = ensure(paint)
        if (d == null) {
            canvas.drawText(latex, x, y.toFloat(), paint)
            return
        }
        val h = d.intrinsicHeight.toFloat()
        canvas.save()
        canvas.translate(x, y - h * baselineRatio * scale)
        canvas.scale(scale, scale)
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        d.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val MAX_WIDTH_RATIO = 0.8f
    }
}
