package com.decikeeper.app.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 相对亮度与色彩对比度计算工具。
 * 杜绝使用 HSV/Red 通道差异判断撞色，完全基于人眼感知亮度（Relative Luminance）动态决策高对比度文字。
 */
object ContrastHelper {

    const val COLOR_BLACK: Int = -0x1000000 // 0xFF000000
    const val COLOR_WHITE: Int = -0x1        // 0xFFFFFFFF

    /**
     * 计算 sRGB 单通道的线性伽马值
     */
    private fun sRgbToLinear(c: Int): Double {
        val s = (c and 0xFF) / 255.0
        return if (s <= 0.04045) {
            s / 12.92
        } else {
            ((s + 0.055) / 1.055).pow(2.4)
        }
    }

    /**
     * 计算 ARGB 颜色的相对感知亮度 (Relative Luminance)
     * L = 0.2126 * R_lin + 0.7152 * G_lin + 0.0722 * B_lin
     * 取值范围: [0.0, 1.0]
     */
    fun calculateLuminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val rLin = sRgbToLinear(r)
        val gLin = sRgbToLinear(g)
        val bLin = sRgbToLinear(b)

        return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
    }

    /**
     * 计算前景与背景的 WCAG 对比度
     * Contrast = (L1 + 0.05) / (L2 + 0.05), 取值范围 [1.0, 21.0]
     */
    fun calculateContrast(foreground: Int, background: Int): Double {
        val l1 = calculateLuminance(foreground)
        val l2 = calculateLuminance(background)
        val bright = max(l1, l2)
        val dark = min(l1, l2)
        return (bright + 0.05) / (dark + 0.05)
    }

    /**
     * 给定背景色，在纯黑 (#000000) 和纯白 (#FFFFFF) 中自动选择对比度更高、最清晰易读的文字颜色。
     */
    fun getOptimalTextColor(backgroundColor: Int): Int {
        val blackContrast = calculateContrast(COLOR_BLACK, backgroundColor)
        val whiteContrast = calculateContrast(COLOR_WHITE, backgroundColor)
        return if (blackContrast >= whiteContrast) COLOR_BLACK else COLOR_WHITE
    }

    /**
     * 校验对比度是否满足 WCAG AA 级标准 (普通字 >= 4.5:1, 大号/粗体 >= 3.0:1)
     */
    fun isWcagCompliant(foreground: Int, background: Int, isLargeOrBold: Boolean = false): Boolean {
        val contrast = calculateContrast(foreground, background)
        val threshold = if (isLargeOrBold) 3.0 else 4.5
        return contrast >= threshold
    }
}
