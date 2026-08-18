package com.ironwill.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.TextView

/**
 * Req-1 扩展：支持按词长按的 TextView。
 *
 * 用于选词填空题目段落：用户长按段落中任意一个英文单词时，通过 onWordPressed 回调该单词。
 * 内部在 onTouchEvent 记录按下坐标，在 performLongClick 里用 Layout 的 getLineForVertical /
 * getOffsetForHorizontal 把坐标换算为字符偏移，再交给顶层纯函数 findWordAtIndex 取词。
 *
 * 长按到空白 / 中文 / 标点 / 选词空位 [    ] 时找不到词，返回 super.performLongClick()，
 * 不破坏系统默认行为。全程 try/catch，保证 a11y 覆盖层稳定。
 */
open class WordLookupTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    /** 长按命中某个英文单词时回调。 */
    var onWordPressed: ((word: String) -> Unit)? = null

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        // 允许长按；保持非 clickable，避免干扰短按 / 滚动。
        isLongClickable = true
        isClickable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.x
            lastTouchY = event.y
        }
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        try {
            val layout = layout ?: return super.performLongClick()
            val text = text?.toString().orEmpty()
            if (text.isEmpty() || layout.lineCount == 0) return super.performLongClick()

            val padTop = totalPaddingTop.toFloat()
            val padLeft = totalPaddingLeft.toFloat()
            val x = lastTouchX - padLeft
            val y = lastTouchY - padTop

            var line = layout.getLineForVertical(y.toInt())
            line = line.coerceIn(0, layout.lineCount - 1)

            // 若落点超过该行实际宽度（靠近行尾），钳制到该行末尾。
            val lineRight = layout.getLineRight(line)
            val lineLeft = layout.getLineLeft(line)
            val offX = x.coerceAtLeast(0f).coerceIn(lineLeft, lineRight)

            var offset = layout.getOffsetForHorizontal(line, offX)
            offset = offset.coerceIn(0, text.length - 1)

            val word = findWordAtIndex(text, offset)
            if (word != null) {
                onWordPressed?.invoke(word)
                return true
            }
        } catch (e: Exception) {
            // 任何异常都回退到系统默认长按，绝不中断覆盖层。
        }
        return super.performLongClick()
    }
}