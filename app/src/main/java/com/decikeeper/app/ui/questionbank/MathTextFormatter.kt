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

    fun format(text: CharSequence?): CharSequence {
        if (text == null) return ""
        if (!MathText.needsFormatting(text)) return text
        val pieces = MathText.parse(text.toString())
        // 解析后只有一段原样文字：直接返回原文，避免无谓的 Spannable
        val only = pieces.singleOrNull()
        if (only is MathText.Piece.Plain) return only.text
        val out = SpannableStringBuilder()
        append(out, pieces)
        return out
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
