package com.ironwill.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.ironwill.app.R


class FocusRulesFragment : Fragment() {

    companion object {
        const val TAB_TIME = 0
        const val TAB_APP_WINDOW = 1
    }

    private var pendingTabIndex: Int? = null
    private var viewPager: ViewPager2? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_focus_rules, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.tab_focus_rules)
        val pager = view.findViewById<ViewPager2>(R.id.pager_focus_rules)
        viewPager = pager

        pager.adapter = FocusRulesPagerAdapter(this)
        pager.offscreenPageLimit = 1

        TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = if (position == TAB_TIME) "勿扰时段" else "应用时间窗"
        }.attach()

        pendingTabIndex?.let { index ->
            pager.setCurrentItem(index, false)
            pendingTabIndex = null
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 当 FocusRulesFragment 从隐藏切回显示时（例如从 RulesFragment 切换生效策略后返回），
        // 刷新 ViewPager2 子 fragment 的 UI。hide/show 不会触发子 fragment 的 onResume。
        if (!hidden && isAdded && view != null) {
            childFragmentManager.fragments.forEach { fragment ->
                when (fragment) {
                    is TimeConfigFragment -> fragment.reloadData()
                    is AppWindowFragment -> fragment.refreshList()
                }
            }
        }
    }

    fun selectTab(index: Int) {
        val pager = viewPager
        if (pager == null) {
            pendingTabIndex = index
        } else {
            pager.setCurrentItem(index, false)
        }
    }

    private class FocusRulesPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return if (position == TAB_TIME) {
                TimeConfigFragment()
            } else {
                AppWindowFragment()
            }
        }
    }
}
