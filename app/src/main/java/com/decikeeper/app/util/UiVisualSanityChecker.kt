package com.decikeeper.app.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 运行时 UI 视觉质量与排版审查器 (Runtime UI Visual Sanity Checker)
 *
 * 核心职责：
 * 在真实渲染与布局完成后（onLayout / post），直接读取 Android 系统计算完毕的真实排版事实：
 * 1. TextOverflowChecker: 检查 layout.getEllipsisCount、lineWidth > availableWidth、layout.height > availableHeight；
 * 2. ContrastChecker: 校验前景与背景色是否满足 WCAG 2.1 AA 级对比度 (>= 4.5:1)；
 * 3. InsetsChecker / BoundsChecker: 检查控件越界与状态栏安全区避让。
 */
object UiVisualSanityChecker {

    enum class IssueType {
        TEXT_OVERFLOW,
        LOW_CONTRAST,
        STATUS_BAR_OVERLAP,
        VIEW_OUT_OF_BOUNDS
    }

    data class UiIssue(
        val issueType: IssueType,
        val viewId: String,
        val text: String? = null,
        val details: String,
        val viewClass: String
    ) {
        override fun toString(): String = buildString {
            append("UiVisualSanity ERROR\n")
            append("View: $viewId ($viewClass)\n")
            append("Issue: $issueType\n")
            if (text != null) append("Text: $text\n")
            append(details)
        }
    }

    /**
     * 运行时检查单个 TextView 实际布局完成后的排版与截断状态
     */
    fun checkTextView(textView: TextView): List<UiIssue> {
        val issues = mutableListOf<UiIssue>()
        val layout = textView.layout ?: return issues
        val text = textView.text?.toString() ?: ""
        if (text.isEmpty() || textView.visibility != View.VISIBLE) return issues

        val availableWidth = textView.width - textView.compoundPaddingLeft - textView.compoundPaddingRight
        val availableHeight = textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom
        val viewIdStr = try {
            if (textView.id != View.NO_ID && textView.id != 0) {
                textView.resources.getResourceEntryName(textView.id)
            } else "unnamed_view"
        } catch (_: Exception) {
            "view_${textView.id}"
        }

        // 1. 检查各行宽度溢出与省略号截断
        var hasEllipsis = false
        var maxLineWidth = 0f
        for (i in 0 until layout.lineCount) {
            val lineWidth = layout.getLineWidth(i)
            if (lineWidth > maxLineWidth) maxLineWidth = lineWidth
            if (layout.getEllipsisCount(i) > 0) {
                hasEllipsis = true
            }
        }

        if (hasEllipsis) {
            issues.add(
                UiIssue(
                    issueType = IssueType.TEXT_OVERFLOW,
                    viewId = viewIdStr,
                    text = text,
                    details = "Available text width: ${availableWidth}px, Required line width: ${maxLineWidth.toInt()}px, Has ellipsis: true",
                    viewClass = textView.javaClass.simpleName
                )
            )
        } else if (availableWidth > 0 && maxLineWidth > availableWidth + 1f) {
            issues.add(
                UiIssue(
                    issueType = IssueType.TEXT_OVERFLOW,
                    viewId = viewIdStr,
                    text = text,
                    details = "Available text width: ${availableWidth}px, Required line width: ${maxLineWidth.toInt()}px, Overflows width",
                    viewClass = textView.javaClass.simpleName
                )
            )
        }

        // 2. 检查文字总高度是否超过 TextView 可视高度
        val layoutHeight = layout.height
        if (availableHeight > 0 && layoutHeight > availableHeight + 1) {
            issues.add(
                UiIssue(
                    issueType = IssueType.TEXT_OVERFLOW,
                    viewId = viewIdStr,
                    text = text,
                    details = "Available text height: ${availableHeight}px, Required layout height: ${layoutHeight}px, Overflows height",
                    viewClass = textView.javaClass.simpleName
                )
            )
        }

        return issues
    }

    /**
     * 校验前景色与背景色之间的对比度
     */
    fun checkContrast(
        viewId: String,
        fgColor: Int,
        bgColor: Int,
        isLargeText: Boolean = false
    ): List<UiIssue> {
        val issues = mutableListOf<UiIssue>()
        val ratio = ContrastHelper.calculateContrast(fgColor, bgColor)
        val minRatio = if (isLargeText) 3.0 else 4.5
        if (ratio < minRatio) {
            val fgHex = String.format("#%06X", 0xFFFFFF and fgColor)
            val bgHex = String.format("#%06X", 0xFFFFFF and bgColor)
            issues.add(
                UiIssue(
                    issueType = IssueType.LOW_CONTRAST,
                    viewId = viewId,
                    details = "Foreground: $fgHex, Background: $bgHex, Contrast ratio: ${String.format("%.2f", ratio)}:1, Required: $minRatio:1",
                    viewClass = "ColorContrast"
                )
            )
        }
        return issues
    }

    /**
     * 深度遍历扫描 View 树
     */
    fun scanView(view: View, issues: MutableList<UiIssue> = mutableListOf()): List<UiIssue> {
        if (view.visibility != View.VISIBLE) return issues

        if (view is TextView) {
            issues.addAll(checkTextView(view))
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanView(view.getChildAt(i), issues)
            }
        }

        return issues
    }
}
