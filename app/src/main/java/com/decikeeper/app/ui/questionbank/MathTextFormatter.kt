package com.decikeeper.app.ui.questionbank

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import com.decikeeper.app.manager.questionbank.MathText

/**
 * 把题目里的数学写法渲染成真正的上下标（Spannable），常用符号也一并转成 Unicode。
 * 解析规则见 [MathText]；不含数学写法的文字原样返回，不会改变普通题目的显示。
 */
object MathTextFormatter {

    private const val SCRIPT_SCALE = 0.72f
    private const val PLACEHOLDER = "￼"

    fun format(text: CharSequence?): CharSequence {
        if (text == null) return ""
        if (!MathText.needsFormatting(text)) return text
        val out = SpannableStringBuilder()
        var styled = false
        MathText.splitFormulas(text.toString()).forEach { segment ->
            when (segment) {
                is MathText.Segment.Text -> {
                    val pieces = MathText.parse(segment.text)
                    if (pieces.any { it !is MathText.Piece.Plain }) styled = true
                    append(out, pieces)
                }
                is MathText.Segment.Formula -> {
                    if (MathText.isEngineEligible(segment.latex) && MathEngine.isReady()) {
                        // 用对象替换字符占位，整段公式由排版引擎画成一个“字”
                        val start = out.length
                        out.append(PLACEHOLDER)
                        out.setSpan(MathFormulaSpan(segment.latex), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        styled = true
                    } else {
                        // 含中文或引擎不可用：退回轻量上下标显示
                        val pieces = MathText.parse(segment.latex)
                        if (pieces.any { it !is MathText.Piece.Plain }) styled = true
                        append(out, pieces)
                    }
                }
            }
        }
        // 没有任何样式：直接返回纯文字，避免无谓的 Spannable
        return if (styled) out else out.toString()
    }

    private fun append(out: SpannableStringBuilder, pieces: List<MathText.Piece>) {
        pieces.forEach { piece ->
            when (piece) {
                is MathText.Piece.Plain -> out.append(piece.text)
                is MathText.Piece.Sup -> appendScript(out, piece.pieces, SuperscriptSpan())
                is MathText.Piece.Sub -> appendScript(out, piece.pieces, SubscriptSpan())
            }
        }
    }

    private fun appendScript(out: SpannableStringBuilder, inner: List<MathText.Piece>, shift: Any) {
        val start = out.length
        append(out, inner)
        val end = out.length
        if (end > start) {
            // 先位移、再缩小，嵌套时缩放会逐层叠加
            out.setSpan(shift, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(SCRIPT_SCALE), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
