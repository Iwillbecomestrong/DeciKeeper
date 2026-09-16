package com.decikeeper.app.ui

import com.decikeeper.app.ui.HomeFragment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 初始化设置向导卡片（Setup Guide）展示与隐藏判定单元测试。
 */
class SetupGuideLogicTest {

    @Test
    fun testDismissedHidesCard() {
        // 用户已主动点击跳过/关闭，无论各项配置如何均应隐藏向导卡片
        assertFalse(
            "主动跳过后应隐藏卡片",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = true,
                permsDone = false,
                blacklistDone = false,
                dndDone = false,
                alarmDone = false
            )
        )
        assertFalse(
            "主动跳过后即使全部完成也隐藏",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = true,
                permsDone = true,
                blacklistDone = true,
                dndDone = true,
                alarmDone = true
            )
        )
    }

    @Test
    fun testMissingPermissionsShowsCard() {
        // 基础权限未完成时，即使功能分支已完成也必须保持展示
        assertTrue(
            "权限未给齐且有闹钟时仍需提示基础权限",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = false,
                blacklistDone = false,
                dndDone = false,
                alarmDone = true
            )
        )
        assertTrue(
            "权限未给齐且有时间规则时仍需提示基础权限",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = false,
                blacklistDone = true,
                dndDone = true,
                alarmDone = false
            )
        )
    }

    @Test
    fun testAlarmOnlyBranchCompletesSetup() {
        // 场景：用户仅使用挑战闹钟（防赖床）
        // 只要权限搞定 + 建立了至少一个闹钟，初始化即算完成并自动隐藏
        assertFalse(
            "基础权限完成且设置了挑战闹钟，向导卡片应自动隐藏",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = true,
                blacklistDone = false,
                dndDone = false,
                alarmDone = true
            )
        )
    }

    @Test
    fun testTimeRulesBranchCompletesSetup() {
        // 场景：用户仅使用时间规则管控（防沉迷）
        // 权限搞定 + 黑白名单与勿扰段齐全，向导卡片自动隐藏
        assertFalse(
            "基础权限完成且时间规则分支齐全（黑白名单+勿扰时段），向导卡片应自动隐藏",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = true,
                blacklistDone = true,
                dndDone = true,
                alarmDone = false
            )
        )
    }

    @Test
    fun testPartialTimeRulesShowsCard() {
        // 场景：时间规则分支未设置完整（仅设黑名单或仅设勿扰段，且未设闹钟）
        assertTrue(
            "仅设黑名单未设勿扰段，卡片应继续展示",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = true,
                blacklistDone = true,
                dndDone = false,
                alarmDone = false
            )
        )
        assertTrue(
            "仅设勿扰段未设黑名单，卡片应继续展示",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = true,
                blacklistDone = false,
                dndDone = true,
                alarmDone = false
            )
        )
    }

    @Test
    fun testBothBranchesDoneHidesCard() {
        // 两者都配置齐全时自然隐藏
        assertFalse(
            "全部配置齐全时卡片应隐藏",
            HomeFragment.shouldShowSetupGuide(
                isDismissed = false,
                permsDone = true,
                blacklistDone = true,
                dndDone = true,
                alarmDone = true
            )
        )
    }
}
