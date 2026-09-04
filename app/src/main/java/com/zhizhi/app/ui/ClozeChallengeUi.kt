package com.zhizhi.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import com.zhizhi.app.R
import com.zhizhi.app.manager.AlarmChallengeManager

/**
 * Small UI-only safeguards for the cloze challenge.
 *
 * The cloze passage view is already a custom WordLookupTextView, so keeping these
 * corrections here avoids coupling the question manager or the large alarm overlay
 * controller to device-specific rendering details.
 */
internal object ClozeChallengeUi {
    private const val WORD_DETAIL_TAG = "alarm_cloze_word_detail_overlay"

    /**
     * The imported exam pool uses exactly four underscores for the real target blank.
     * Old OCR occasionally leaves shorter underline fragments elsewhere in the excerpt;
     * remove those fragments so the challenge presents one meaningful blank only.
     */
    @JvmStatic
    fun sanitizePassageForDisplay(input: String): String {
        if (input.isEmpty()) return input

        var keptTarget = false
        var text = Regex("_{4,}").replace(input) {
            if (!keptTarget) {
                keptTarget = true
                "____"
            } else {
                ""
            }
        }
        text = Regex("(?<!_)_{1,3}(?!_)").replace(text, "")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Allow long choices such as phrases to use two lines instead of clipping. */
    fun configureOptionButtons(view: View) {
        val root = view.rootView ?: return
        val optionIds = intArrayOf(
            R.id.btn_cloze_a,
            R.id.btn_cloze_b,
            R.id.btn_cloze_c,
            R.id.btn_cloze_d
        )
        optionIds.forEach { id ->
            val button = root.findViewById<Button>(id) ?: return@forEach
            val params = button.layoutParams ?: return@forEach
            params.height = dp(view, 64)
            button.layoutParams = params
            button.maxLines = 2
            button.gravity = Gravity.CENTER
            button.setPadding(dp(view, 6), 0, dp(view, 6), 0)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button,
                10,
                15,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /**
     * Show word details as the last child of the alarm root instead of as a second
     * WindowManager/AlertDialog window. This guarantees it stays visually above the
     * challenge while preserving the alarm overlay's anti-escape window semantics.
     */
    fun showWordDetailInsideAlarmRoot(anchor: View, rawWord: String): Boolean {
        val root = anchor.rootView?.findViewById<FrameLayout>(R.id.root_container) ?: return false
        val word = rawWord.trim()
        if (word.isEmpty()) return false

        root.findViewWithTag<View>(WORD_DETAIL_TAG)?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val lib = AlarmChallengeManager.lookupWordWithLemma(word)
        val phonetic = when {
            lib == null -> ""
            lib.us.isNotEmpty() -> lib.us
            else -> lib.uk
        }
        val translation = lib?.translation?.ifEmpty { "暂无释义" } ?: "暂无释义"
        val translationForBookmark = lib?.translation ?: ""
        val phoneticForBookmark = phonetic

        val overlay = FrameLayout(context).apply {
            tag = WORD_DETAIL_TAG
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.argb(190, 0, 0, 0))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(anchor, 22), dp(anchor, 18), dp(anchor, 22), dp(anchor, 18))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18f * density
            }
            elevation = 18f * density
        }

        card.addView(TextView(context).apply {
            text = "单词详情"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#546E7A"))
        })

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(anchor, 10), 0, 0)
        }
        titleRow.addView(TextView(context).apply {
            text = word
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#006064"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        var bookmarked = AlarmChallengeManager.getBookmarkedWords(context)
            .any { it.word.equals(word, ignoreCase = true) }
        val star = TextView(context).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(dp(anchor, 10), 0, 0, 0)
        }
        fun renderStar() {
            star.text = if (bookmarked) "★" else "☆"
            star.setTextColor(
                if (bookmarked) Color.parseColor("#FBC02D") else Color.parseColor("#90A4AE")
            )
        }
        renderStar()
        star.setOnClickListener {
            bookmarked = !bookmarked
            if (bookmarked) {
                AlarmChallengeManager.bookmarkWord(
                    context,
                    word,
                    translationForBookmark,
                    phoneticForBookmark
                )
                Toast.makeText(context, "已收藏单词: $word", Toast.LENGTH_SHORT).show()
            } else {
                AlarmChallengeManager.removeBookmarkedWord(context, word)
                Toast.makeText(context, "已取消收藏: $word", Toast.LENGTH_SHORT).show()
            }
            renderStar()
        }
        titleRow.addView(star, LinearLayout.LayoutParams(dp(anchor, 56), dp(anchor, 56)))
        card.addView(titleRow)

        if (phonetic.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = phonetic
                textSize = 14f
                setTextColor(Color.parseColor("#607D8B"))
                setPadding(0, dp(anchor, 4), 0, 0)
            })
        }

        card.addView(TextView(context).apply {
            text = translation
            textSize = 16f
            setTextColor(Color.parseColor("#263238"))
            setPadding(0, dp(anchor, 12), 0, dp(anchor, 14))
        })

        val close = Button(context).apply {
            text = "关闭"
            isAllCaps = false
            setOnClickListener { root.removeView(overlay) }
        }
        card.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(anchor, 48)
            )
        )

        overlay.setOnClickListener { root.removeView(overlay) }
        overlay.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dp(anchor, 28)
                marginEnd = dp(anchor, 28)
            }
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bringToFront()
        overlay.requestFocus()
        return true
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
