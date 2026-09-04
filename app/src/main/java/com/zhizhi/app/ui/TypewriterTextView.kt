/**
 * =================================================================================
 * [Context Header]
 * * @file: TypewriterTextView.kt
 * * @role: UI
 * * @description: 打字机动画效果的自定义 TextView。
 * * @responsibilities:
 * 1. 提供逐字显示动画。
 * 2. 支持自定义速度与文本。
 * * @context:
 * - 上游: RulesFragment
 * - 下游: Android TextView
 * * @constraints (必读):
 * 1. 动画需主线程安全。
 * 2. 避免内存泄漏。
 * 3. 处理空文本。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class TypewriterTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var mText: CharSequence? = null
    private var mIndex = 0
    private var mDelay: Long = 50 // Default 50ms per char
    private val mHandler = Handler(Looper.getMainLooper())
    private var onComplete: (() -> Unit)? = null

    private val characterAdder = object : Runnable {
        override fun run() {
            mText?.let { text ->
                setText(text.subSequence(0, mIndex++))
                if (mIndex <= (mText?.length ?: 0)) {
                    mHandler.postDelayed(this, mDelay)
                } else {
                    onComplete?.invoke()
                }
            }
        }
    }

    fun animateText(text: CharSequence, delay: Long = 50, onAnimationComplete: (() -> Unit)? = null) {
        mText = text
        mIndex = 0
        mDelay = delay
        this.text = ""
        onComplete = onAnimationComplete
        mHandler.removeCallbacks(characterAdder)
        mHandler.postDelayed(characterAdder, mDelay)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mHandler.removeCallbacks(characterAdder)
    }
}
