package com.zhizhi.app.statistics

import android.content.Context
import com.zhizhi.app.data.statistics.AppCategory
import com.zhizhi.app.manager.statistics.StatisticsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatisticsCategoryResolutionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(StatisticsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun testNetEaseCloudMusic_ClassifiedAsAudio_NotGame() {
        val pkg = "com.netease.cloudmusic"
        assertFalse("网易云音乐绝不能判定为游戏", StatisticsRepository.isGame(context, pkg, null))
        val cat = StatisticsRepository.resolveCategory(context, pkg, null)
        assertEquals("网易云音乐必须判定为音乐音频", AppCategory.AUDIO, cat)
    }

    @Test
    fun testUURemoteAndRemoteTools_ClassifiedAsToolsSystem_NotGame() {
        val uuPkg = "com.netease.gameremote"
        val uuAltPkg = "com.netease.uu"
        val sunloginPkg = "com.oray.sunlogin"
        val todeskPkg = "com.todesk"

        assertFalse("UU远程不能判定为游戏", StatisticsRepository.isGame(context, uuPkg, null))
        assertFalse("UU加速/控制不能判定为游戏", StatisticsRepository.isGame(context, uuAltPkg, null))
        assertFalse("向日葵远程不能判定为游戏", StatisticsRepository.isGame(context, sunloginPkg, null))
        assertFalse("ToDesk不能判定为游戏", StatisticsRepository.isGame(context, todeskPkg, null))

        assertEquals("UU远程必须归为系统与工具", AppCategory.TOOLS_SYSTEM, StatisticsRepository.resolveCategory(context, uuPkg, null))
        assertEquals("UU加速必须归为系统与工具", AppCategory.TOOLS_SYSTEM, StatisticsRepository.resolveCategory(context, uuAltPkg, null))
        assertEquals("向日葵必须归为系统与工具", AppCategory.TOOLS_SYSTEM, StatisticsRepository.resolveCategory(context, sunloginPkg, null))
        assertEquals("ToDesk必须归为系统与工具", AppCategory.TOOLS_SYSTEM, StatisticsRepository.resolveCategory(context, todeskPkg, null))
    }

    @Test
    fun testNetEaseGames_CorrectlyClassifiedAsGame() {
        val eggParty = "com.netease.party.mobile"
        val sky = "com.netease.sky.android"
        val onmyoji = "com.netease.onmyoji"
        val mc = "com.netease.mc.aligames"

        assertTrue("蛋仔派对必须识别为游戏", StatisticsRepository.isGame(context, eggParty, null))
        assertTrue("光遇必须识别为游戏", StatisticsRepository.isGame(context, sky, null))
        assertTrue("阴阳师必须识别为游戏", StatisticsRepository.isGame(context, onmyoji, null))
        assertTrue("我的世界网易版必须识别为游戏", StatisticsRepository.isGame(context, mc, null))

        assertEquals(AppCategory.GAME, StatisticsRepository.resolveCategory(context, eggParty, null))
        assertEquals(AppCategory.GAME, StatisticsRepository.resolveCategory(context, sky, null))
    }

    @Test
    fun testMainstreamApps_CategorizedAccurately() {
        assertEquals("微信为社交通讯", AppCategory.COMMUNICATION, StatisticsRepository.resolveCategory(context, "com.tencent.mm", null))
        assertEquals("QQ为社交通讯", AppCategory.COMMUNICATION, StatisticsRepository.resolveCategory(context, "com.tencent.mobileqq", null))
        assertEquals("抖音为视频娱乐", AppCategory.VIDEO, StatisticsRepository.resolveCategory(context, "com.ss.android.ugc.aweme", null))
        assertEquals("B站为视频娱乐", AppCategory.VIDEO, StatisticsRepository.resolveCategory(context, "tv.danmaku.bili", null))
        assertEquals("ChatGPT为AI与效率", AppCategory.AI_PRODUCTIVITY, StatisticsRepository.resolveCategory(context, "com.openai.chatgpt", null))
        assertEquals("DeepSeek为AI与效率", AppCategory.AI_PRODUCTIVITY, StatisticsRepository.resolveCategory(context, "com.deepseek.chat", null))
        assertEquals("豆包为AI与效率", AppCategory.AI_PRODUCTIVITY, StatisticsRepository.resolveCategory(context, "com.doubao", null))
        assertEquals("王者荣耀为游戏", AppCategory.GAME, StatisticsRepository.resolveCategory(context, "com.tencent.tmgp.sgame", null))
    }

    @Test
    fun testUserCustomCategoryOverride_TakesHighestPrecedence() {
        val wxPkg = "com.tencent.mm"
        assertEquals(AppCategory.COMMUNICATION, StatisticsRepository.resolveCategory(context, wxPkg, null))

        // 用户将微信手动指定为 AI与效率
        StatisticsRepository.setCustomCategory(context, wxPkg, AppCategory.AI_PRODUCTIVITY)
        assertEquals("用户自定义后必须优先返回 AI与效率", AppCategory.AI_PRODUCTIVITY, StatisticsRepository.resolveCategory(context, wxPkg, null))

        // 用户将网易云音乐手动指定为 系统与工具
        val cloudMusicPkg = "com.netease.cloudmusic"
        StatisticsRepository.setCustomCategory(context, cloudMusicPkg, AppCategory.TOOLS_SYSTEM)
        assertEquals(AppCategory.TOOLS_SYSTEM, StatisticsRepository.resolveCategory(context, cloudMusicPkg, null))

        // 清除自定义
        StatisticsRepository.setCustomCategory(context, wxPkg, null)
        assertEquals("清除后恢复默认社交通讯", AppCategory.COMMUNICATION, StatisticsRepository.resolveCategory(context, wxPkg, null))
    }
}
