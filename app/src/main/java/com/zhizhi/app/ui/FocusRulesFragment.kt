package com.zhizhi.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.zhizhi.app.R
import com.zhizhi.app.manager.EffectiveActivationPolicyManager


class FocusRulesFragment : Fragment() {

    companion object {
        const val TAB_TIME = 0
        const val TAB_APP_WINDOW = 1
    }

    private var pendingTabIndex: Int? = null
    private var viewPager: ViewPager2? = null
    private var tvPolicyCurrent: TextView? = null
    private var tvPolicyScope: TextView? = null

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
        tvPolicyCurrent = view.findViewById(R.id.tv_time_rule_policy_current)
        tvPolicyScope = view.findViewById(R.id.tv_time_rule_policy_scope)

        pager.adapter = FocusRulesPagerAdapter(this)
        pager.offscreenPageLimit = 1

        TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = if (position == TAB_TIME) "勿扰时段" else "应用时间窗"
        }.attach()

        view.findViewById<View>(R.id.card_time_rule_policy).setOnClickListener {
            showTimeRulePolicyDialog()
        }
        updatePolicyCard()

        pendingTabIndex?.let { index ->
            pager.setCurrentItem(index, false)
            pendingTabIndex = null
        }
    }

    override fun onResume() {
        super.onResume()
        updatePolicyCard()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 时间规则页重新显示时，刷新两类规则与当前生效策略。
        if (!hidden && isAdded && view != null) {
            refreshChildRuleUi()
            updatePolicyCard()
        }
    }

    override fun onDestroyView() {
        tvPolicyCurrent = null
        tvPolicyScope = null
        viewPager = null
        super.onDestroyView()
    }

    private fun updatePolicyCard() {
        if (!isAdded) return
        val policy = EffectiveActivationPolicyManager.getPolicy(requireContext())
        val title = EffectiveActivationPolicyManager.getPolicyTitle(policy)
        val description = EffectiveActivationPolicyManager.getPolicyDescription(policy)
        tvPolicyCurrent?.text = "当前：$title\n$description"
        tvPolicyScope?.text = EffectiveActivationPolicyManager.getPolicyScopeDescription()
    }

    private fun showTimeRulePolicyDialog() {
        val context = requireContext()
        val policies = arrayOf(
            EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE,
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        )
        val current = EffectiveActivationPolicyManager.getPolicy(context)
        val checkedIndex = policies.indexOf(current).coerceAtLeast(0)
        val labels = arrayOf(
            "宽严有别（推荐）\n加严立即生效，放宽在下一刷新时刻生效",
            "全部次日\n勿扰时段和应用时间窗的所有修改都在下一刷新时刻生效"
        )

        AlertDialog.Builder(context)
            .setTitle("时间规则生效策略")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = policies[which]
                if (selected != EffectiveActivationPolicyManager.getPolicy(context)) {
                    EffectiveActivationPolicyManager.setPolicy(context, selected)
                    updatePolicyCard()
                    refreshChildRuleUi()
                    Toast.makeText(
                        context,
                        "已切换为：${EffectiveActivationPolicyManager.getPolicyTitle(selected)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshChildRuleUi() {
        childFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is TimeConfigFragment -> fragment.reloadData()
                is AppWindowFragment -> fragment.refreshList()
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
