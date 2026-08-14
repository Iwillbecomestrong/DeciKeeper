package com.ironwill.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v3.1.1 Bug-Fix 防回归: ClozePagerAdapter 子页 LayoutParams 必须 MATCH_PARENT.
 *
 * v3.1 闪退根因:
 *   java.lang.IllegalStateException: Pages must fill the whole ViewPager2 (use match_parent)
 *
 * 根因: 子页 FrameLayout layoutParams.height = WRAP_CONTENT, 违反 ViewPager2 硬约束.
 * 修复: 子页 FrameLayout height 改 MATCH_PARENT, card layoutParams 改 MATCH_PARENT.
 *
 * 本测试验证:
 * 1) onCreateViewHolder 返回的 FrameLayout.layoutParams.height == MATCH_PARENT
 * 2) onCreateViewHolder 返回的 FrameLayout.layoutParams.width == MATCH_PARENT
 * 3) onBindViewHolder 后 card 的 layoutParams.height == MATCH_PARENT
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClozePagerAdapterTest {

    @Test
    fun `ClozePagerAdapter 子页 LayoutParams width MATCH_PARENT`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "Test sentence with ____ blank.",
            answer = "word1",
            options = listOf("word1", "word2", "word3", "word4"),
            optionMeanings = listOf("翻译1", "翻译2", "翻译3", "翻译4"),
            answerTranslation = "翻译1",
            passageTranslation = "测试翻译",
            grammarExplanation = "",
            sourceYear = 1986,
            sourceQuestionNo = 1,
            fullPassage = "",
            sourceRepo = ""
        )
        val entries = listOf(LockWindowManager.SessionClozeEntry(cloze, "correct"))
        // 反射访问 private 嵌套类 (因为 object LockWindowManager 内不能用 inner class)
        val adapterClass = Class.forName("com.ironwill.app.LockWindowManager\$ClozePagerAdapter")
        val constructor = adapterClass.getDeclaredConstructors().first()
        constructor.isAccessible = true
        val adapter = constructor.newInstance(ctx, entries, 1.0f)

        val onCreateViewHolder = adapterClass.getDeclaredMethod(
            "onCreateViewHolder", android.view.ViewGroup::class.java, Int::class.javaPrimitiveType
        )
        onCreateViewHolder.isAccessible = true
        // 用一个虚拟 parent ViewGroup
        val fakeParent = android.widget.LinearLayout(ctx)
        val vh = onCreateViewHolder.invoke(adapter, fakeParent, 0)
        val vhRoot = vh::class.java.getDeclaredField("root").apply { isAccessible = true }.get(vh) as View
        assertNotNull("子页 root 不为空", vhRoot)
        val lp = vhRoot.layoutParams
        assertNotNull("子页 layoutParams 不为空", lp)
        assertEquals(
            "v3.1.1 fix: 子页宽必须 MATCH_PARENT",
            ViewGroup.LayoutParams.MATCH_PARENT, lp.width
        )
    }

    @Test
    fun `ClozePagerAdapter 子页 LayoutParams height MATCH_PARENT — v3_1 闪退根因`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "Test sentence with ____ blank.",
            answer = "word1",
            options = listOf("word1", "word2", "word3", "word4"),
            optionMeanings = listOf("翻译1", "翻译2", "翻译3", "翻译4"),
            answerTranslation = "翻译1",
            passageTranslation = "测试翻译",
            grammarExplanation = "",
            sourceYear = 1986,
            sourceQuestionNo = 1,
            fullPassage = "",
            sourceRepo = ""
        )
        val entries = listOf(LockWindowManager.SessionClozeEntry(cloze, "correct"))
        val adapterClass = Class.forName("com.ironwill.app.LockWindowManager\$ClozePagerAdapter")
        val constructor = adapterClass.getDeclaredConstructors().first()
        constructor.isAccessible = true
        val adapter = constructor.newInstance(ctx, entries, 1.0f)

        val onCreateViewHolder = adapterClass.getDeclaredMethod(
            "onCreateViewHolder", android.view.ViewGroup::class.java, Int::class.javaPrimitiveType
        )
        onCreateViewHolder.isAccessible = true
        val fakeParent = android.widget.LinearLayout(ctx)
        val vh = onCreateViewHolder.invoke(adapter, fakeParent, 0)
        val vhRoot = vh::class.java.getDeclaredField("root").apply { isAccessible = true }.get(vh) as View
        val lp = vhRoot.layoutParams
        assertEquals(
            "v3.1.1 fix: 子页高必须 MATCH_PARENT (ViewPager2 硬约束, 否则 IllegalStateException)",
            ViewGroup.LayoutParams.MATCH_PARENT, lp.height
        )
    }

    @Test
    fun `ClozePagerAdapter 绑定后 card layoutParams height MATCH_PARENT`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "Test sentence with ____ blank.",
            answer = "word1",
            options = listOf("word1", "word2", "word3", "word4"),
            optionMeanings = listOf("翻译1", "翻译2", "翻译3", "翻译4"),
            answerTranslation = "翻译1",
            passageTranslation = "测试翻译",
            grammarExplanation = "",
            sourceYear = 1986,
            sourceQuestionNo = 1,
            fullPassage = "",
            sourceRepo = ""
        )
        val entries = listOf(LockWindowManager.SessionClozeEntry(cloze, "correct"))
        val adapterClass = Class.forName("com.ironwill.app.LockWindowManager\$ClozePagerAdapter")
        val constructor = adapterClass.getDeclaredConstructors().first()
        constructor.isAccessible = true
        val adapter = constructor.newInstance(ctx, entries, 1.0f)

        val onCreateViewHolder = adapterClass.getDeclaredMethod(
            "onCreateViewHolder", android.view.ViewGroup::class.java, Int::class.javaPrimitiveType
        )
        onCreateViewHolder.isAccessible = true
        val fakeParent = android.widget.LinearLayout(ctx)
        val vh = onCreateViewHolder.invoke(adapter, fakeParent, 0)

        val onBindViewHolder = adapterClass.getDeclaredMethod(
            "onBindViewHolder", vh::class.java, Int::class.javaPrimitiveType
        )
        onBindViewHolder.isAccessible = true
        onBindViewHolder.invoke(adapter, vh, 0)

        val vhRoot = vh::class.java.getDeclaredField("root").apply { isAccessible = true }.get(vh) as View
        // 绑定后, frame 内部应有一个子 view (card), card 的 layoutParams 高 MATCH_PARENT
        assertEquals("绑定后 frame 应有 1 个子 view", 1, (vhRoot as android.view.ViewGroup).childCount)
        val card = vhRoot.getChildAt(0)
        val cardLp = card.layoutParams
        assertNotNull("card layoutParams 不为空", cardLp)
        assertEquals(
            "v3.1.1 fix: card 高必须 MATCH_PARENT",
            ViewGroup.LayoutParams.MATCH_PARENT, cardLp.height
        )
    }

    @Test
    fun `ClozePagerAdapter getItemCount 返回 entries 数量`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "x",
            answer = "a",
            options = listOf("a", "b", "c", "d"),
            optionMeanings = listOf("甲", "乙", "丙", "丁"),
            answerTranslation = "甲",
            passageTranslation = "",
            grammarExplanation = "",
            sourceYear = 0,
            sourceQuestionNo = 1,
            fullPassage = "",
            sourceRepo = ""
        )
        val entries = (1..3).map { LockWindowManager.SessionClozeEntry(cloze, "skipped") }
        val adapterClass = Class.forName("com.ironwill.app.LockWindowManager\$ClozePagerAdapter")
        val constructor = adapterClass.getDeclaredConstructors().first()
        constructor.isAccessible = true
        val adapter = constructor.newInstance(ctx, entries, 1.0f)
        val getItemCount = adapterClass.getDeclaredMethod("getItemCount")
        getItemCount.isAccessible = true
        assertEquals("3 条 entry", 3, getItemCount.invoke(adapter) as Int)
    }

    // ==================== v3.1.4 追加测试 ====================

    /**
     * v3.1.4 防回归: 卡片用 NestedScrollView 包裹后, 子页 (frame) 内应有 1 个 NestedScrollView
     * 子 view, 高 MATCH_PARENT (避免被平分空间).
     */
    @Test
    fun `NestedScrollView 包裹后 frame 内子 view 高 MATCH_PARENT`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val cloze = AlarmChallengeManager.ClozeQuestion(
            sentence = "Test sentence with ____ blank.",
            answer = "word1",
            options = listOf("word1", "word2", "word3", "word4"),
            optionMeanings = listOf("翻译1", "翻译2", "翻译3", "翻译4"),
            answerTranslation = "翻译1",
            passageTranslation = "测试翻译",
            grammarExplanation = "",
            sourceYear = 1986,
            sourceQuestionNo = 1,
            fullPassage = "",
            sourceRepo = ""
        )
        val entries = listOf(LockWindowManager.SessionClozeEntry(cloze, "correct"))
        val adapterClass = Class.forName("com.ironwill.app.LockWindowManager\$ClozePagerAdapter")
        val constructor = adapterClass.getDeclaredConstructors().first()
        constructor.isAccessible = true
        val adapter = constructor.newInstance(ctx, entries, 1.0f)

        val onCreateViewHolder = adapterClass.getDeclaredMethod(
            "onCreateViewHolder", ViewGroup::class.java, Int::class.javaPrimitiveType
        )
        onCreateViewHolder.isAccessible = true
        val fakeParent = android.widget.LinearLayout(ctx)
        val vh = onCreateViewHolder.invoke(adapter, fakeParent, 0)

        val onBindViewHolder = adapterClass.getDeclaredMethod(
            "onBindViewHolder", vh::class.java, Int::class.javaPrimitiveType
        )
        onBindViewHolder.isAccessible = true
        onBindViewHolder.invoke(adapter, vh, 0)

        val vhRoot = vh::class.java.getDeclaredField("root").apply { isAccessible = true }.get(vh) as View
        // v3.1.1 fix 后, frame 内应有 1 个子 view (NestedScrollView 包裹的卡片)
        assertEquals("frame 内应有 1 个子 view (scrollWrapper)", 1, (vhRoot as ViewGroup).childCount)
        val scrollWrapper = vhRoot.getChildAt(0)
        assertNotNull("scrollWrapper 存在", scrollWrapper)
        // 验证它是 NestedScrollView
        assertTrue(
            "v3.1.1 fix: 包裹卡片的是 NestedScrollView (实际类型: ${scrollWrapper.javaClass.simpleName})",
            scrollWrapper is NestedScrollView
        )
        // 验证 scrollWrapper 的高 MATCH_PARENT (避免被 weight=1 平分空间)
        val lp = scrollWrapper.layoutParams
        assertNotNull("scrollWrapper layoutParams 不为空", lp)
        assertEquals(
            "v3.1.4 fix: scrollWrapper (NestedScrollView) 高必须 MATCH_PARENT",
            ViewGroup.LayoutParams.MATCH_PARENT, lp.height
        )
    }

    /**
     * v3.1.4 防回归: Bug-2 守卫行为. onAnyChallengePassed 第一次调用 -> buildReviewCards 被调用 ->
     * layout_alarm_review.visibility = VISIBLE. 第二次调用 -> 守卫阻止 buildReviewCards,
     * 但 layout_alarm_review 仍然 VISIBLE (且不崩).
     */
    @Test
    fun `Bug-2 守卫 二次 onAnyChallengePassed 不重复 buildReviewCards`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        // 用最小 view 树 (仅包含 onAnyChallengePassed 用到的 id), 避免 inflate 完整布局
        // 时遇到 Robolectric 找不到的 drawable/icon 资源.
        val view = android.widget.FrameLayout(ctx)
        val reviewLayout = android.widget.LinearLayout(ctx).apply {
            id = R.id.layout_alarm_review
            visibility = View.GONE  // 模拟 XML 默认值, 守卫首次会放行 buildReviewCards
        }
        view.addView(reviewLayout)

        // 反射注入 mAlarmView 和 mContext 到 LockWindowManager 单例
        val managerClass = LockWindowManager::class.java
        val mAlarmViewField = managerClass.getDeclaredField("mAlarmView").apply { isAccessible = true }
        val mContextField = managerClass.getDeclaredField("mContext").apply { isAccessible = true }
        mAlarmViewField.set(LockWindowManager, view)
        mContextField.set(LockWindowManager, ctx)

        // 反射注入 1 个 SessionPoemEntry 到 sessionPoems, 让 hasReview = true
        val sessionPoemsField = managerClass.getDeclaredField("sessionPoems").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val sessionPoemsList = sessionPoemsField.get(LockWindowManager) as MutableList<Any>
        sessionPoemsList.clear()
        val poem = AlarmChallengeManager.Poem(up = "锄禾日当午", down = "汗滴禾下土")
        val poemEntryCtor = LockWindowManager.SessionPoemEntry::class.java.getDeclaredConstructor(
            AlarmChallengeManager.Poem::class.java, String::class.java, String::class.java
        )
        val poemEntry = poemEntryCtor.newInstance(poem, "correct", null)
        sessionPoemsList.add(poemEntry)

        // 反射调用 onAnyChallengePassed
        val onAnyChallengePassed = managerClass.getDeclaredMethod("onAnyChallengePassed")
        onAnyChallengePassed.isAccessible = true

        // ---- 第一次调用 ----
        onAnyChallengePassed.invoke(LockWindowManager)
        assertEquals(
            "第一次 onAnyChallengePassed 后, layout_alarm_review 应 VISIBLE",
            View.VISIBLE, reviewLayout.visibility
        )

        // ---- 第二次调用 (模拟扫码成功后再次进入) ----
        // 守卫应阻止 buildReviewCards 重新执行, 但 reviewLayout 仍应保持 VISIBLE 且不崩
        onAnyChallengePassed.invoke(LockWindowManager)
        assertEquals(
            "第二次 onAnyChallengePassed 后, layout_alarm_review 应仍然 VISIBLE",
            View.VISIBLE, reviewLayout.visibility
        )

        // ---- 第三次调用, 进一步确认守卫稳定 ----
        onAnyChallengePassed.invoke(LockWindowManager)
        assertEquals(
            "第三次 onAnyChallengePassed 后, layout_alarm_review 应仍然 VISIBLE",
            View.VISIBLE, reviewLayout.visibility
        )

        // 清理: 还原反射注入的状态, 避免污染其他测试
        mAlarmViewField.set(LockWindowManager, null)
        mContextField.set(LockWindowManager, null)
        sessionPoemsList.clear()
    }
}
