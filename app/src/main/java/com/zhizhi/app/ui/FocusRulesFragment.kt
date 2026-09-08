package com.zhizhi.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.zhizhi.app.R
import com.zhizhi.app.manager.EffectiveActivationPolicyManager

class FocusRulesFragment : Fragment() {

    companion object {
        const val TAB_TIME = 0
        const val TAB_APP_WINDOW = 1
    }

    private var pendingTabIndex: Int? = null
    private var rgEffectivePolicy: RadioGroup? = null
    private var policyUiUpdating = false

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
        tabLayout.removeAllTabs()
        val timeTab = tabLayout.newTab().setText("勿扰时段")
        val appTab = tabLayout.newTab().setText("应用时间窗")
        tabLayout.addTab(timeTab)
        tabLayout.addTab(appTab)

        val containerTime = view.findViewById<View>(R.id.container_time_config)
        val containerAppWindow = view.findViewById<View>(R.id.container_app_window)

        if (childFragmentManager.findFragmentByTag("time_config") == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.container_time_config, TimeConfigFragment(), "time_config")
                .replace(R.id.container_app_window, AppWindowFragment(), "app_window")
                .commitNow()
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isTime = (tab?.position == TAB_TIME)
                containerTime.visibility = if (isTime) View.VISIBLE else View.GONE
                containerAppWindow.visibility = if (isTime) View.GONE else View.VISIBLE
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        rgEffectivePolicy = view.findViewById(R.id.rg_effective_policy)
        bindPolicyCopy(view)
        setupEffectivePolicyToggle()
        view.findViewById<TextView>(R.id.btn_effective_policy_help)?.setOnClickListener {
            showEffectivePolicyHelpDialog()
        }
        updateEffectivePolicyUI()

        pendingTabIndex?.let { index ->
            tabLayout.getTabAt(index)?.select()
            pendingTabIndex = null
        }
    }

    override fun onResume() {
        super.onResume()
        updateEffectivePolicyUI()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && view != null) {
            childFragmentManager.fragments.forEach { fragment ->
                when (fragment) {
                    is TimeConfigFragment -> fragment.reloadData()
                    is AppWindowFragment -> fragment.refreshList()
                }
            }
            updateEffectivePolicyUI()
        }
    }

    override fun onDestroyView() {
        rgEffectivePolicy = null
        super.onDestroyView()
    }

    fun selectTab(index: Int) {
        val tabLayout = view?.findViewById<TabLayout>(R.id.tab_focus_rules)
        if (tabLayout == null) {
            pendingTabIndex = index
        } else {
            tabLayout.getTabAt(index)?.select()
        }
    }

    private fun bindPolicyCopy(view: View) {
        val favorable = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        val allNextDay = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY

        view.findViewById<RadioButton>(R.id.rb_policy_favorable)?.text =
            "「${EffectiveActivationPolicyManager.getPolicyTitle(favorable)}」（推荐）\n" +
                EffectiveActivationPolicyManager.getPolicyDescription(favorable)
        view.findViewById<RadioButton>(R.id.rb_policy_all_next_day)?.text =
            "「${EffectiveActivationPolicyManager.getPolicyTitle(allNextDay)}」\n" +
                EffectiveActivationPolicyManager.getPolicyDescription(allNextDay)
    }

    private fun setupEffectivePolicyToggle() {
        rgEffectivePolicy?.setOnCheckedChangeListener { _, checkedId ->
            if (policyUiUpdating || !isAdded) return@setOnCheckedChangeListener

            val context = requireContext()
            val selected = if (checkedId == R.id.rb_policy_all_next_day) {
                EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
            } else {
                EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
            }
            val current = EffectiveActivationPolicyManager.getPolicy(context)
            if (current == selected) return@setOnCheckedChangeListener

            EffectiveActivationPolicyManager.setPolicy(context, selected)
            updateEffectivePolicyUI()
            Toast.makeText(
                context,
                "已切换为：${EffectiveActivationPolicyManager.getPolicyTitle(selected)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateEffectivePolicyUI() {
        if (!isAdded) return

        val policy = EffectiveActivationPolicyManager.getPolicy(requireContext())
        val targetId = if (policy == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY) {
            R.id.rb_policy_all_next_day
        } else {
            R.id.rb_policy_favorable
        }

        policyUiUpdating = true
        rgEffectivePolicy?.check(targetId)
        policyUiUpdating = false
    }

    private fun showEffectivePolicyHelpDialog() {
        val favorable = EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE
        val allNextDay = EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        val message = buildString {
            append(EffectiveActivationPolicyManager.getPolicyScopeDescription())
            append("\n\n")
            append("【${EffectiveActivationPolicyManager.getPolicyTitle(favorable)}（推荐）】\n")
            append(EffectiveActivationPolicyManager.getPolicyDescription(favorable))
            append("\n\n")
            append("【${EffectiveActivationPolicyManager.getPolicyTitle(allNextDay)}】\n")
            append(EffectiveActivationPolicyManager.getPolicyDescription(allNextDay))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("时间规则生效策略")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}
