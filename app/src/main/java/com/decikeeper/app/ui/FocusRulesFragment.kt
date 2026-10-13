package com.decikeeper.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.decikeeper.app.R

class FocusRulesFragment : Fragment() {

    companion object {
        const val TAB_TIME = 0
        const val TAB_APP_WINDOW = 1
        const val TAB_GLOBAL_PARAMS = 2
    }

    private var pendingTabIndex: Int? = null

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
        val globalParamsTab = tabLayout.newTab().setText("规则参数")
        tabLayout.addTab(timeTab)
        tabLayout.addTab(appTab)
        tabLayout.addTab(globalParamsTab)

        val containerTime = view.findViewById<View>(R.id.container_time_config)
        val containerAppWindow = view.findViewById<View>(R.id.container_app_window)
        val containerGlobalParams = view.findViewById<View>(R.id.container_global_params)

        if (childFragmentManager.findFragmentByTag("time_config") == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.container_time_config, TimeConfigFragment(), "time_config")
                .replace(R.id.container_app_window, AppWindowFragment(), "app_window")
                .replace(R.id.container_global_params, GlobalParamsFragment(), "global_params")
                .commitNow()
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isTime = (tab?.position == TAB_TIME)
                val isGlobal = (tab?.position == TAB_GLOBAL_PARAMS)
                containerTime.visibility = if (isTime) View.VISIBLE else View.GONE
                containerAppWindow.visibility = if (!isTime && !isGlobal) View.VISIBLE else View.GONE
                containerGlobalParams.visibility = if (isGlobal) View.VISIBLE else View.GONE
                refreshTabContent(tab?.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                refreshTabContent(tab?.position)
            }
        })

        pendingTabIndex?.let { index ->
            tabLayout.getTabAt(index)?.select()
            pendingTabIndex = null
        }
    }

    private fun refreshTabContent(position: Int?) {
        if (!isAdded || view == null) return
        when (position) {
            TAB_TIME -> (childFragmentManager.findFragmentByTag("time_config") as? TimeConfigFragment)?.reloadData()
            TAB_APP_WINDOW -> (childFragmentManager.findFragmentByTag("app_window") as? AppWindowFragment)?.refreshList()
            TAB_GLOBAL_PARAMS -> (childFragmentManager.findFragmentByTag("global_params") as? GlobalParamsFragment)?.reloadData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && view != null) {
            childFragmentManager.fragments.forEach { fragment ->
                when (fragment) {
                    is TimeConfigFragment -> fragment.reloadData()
                    is AppWindowFragment -> fragment.refreshList()
                    is GlobalParamsFragment -> fragment.reloadData()
                }
            }
        }
    }

    fun selectTab(index: Int) {
        val tabLayout = view?.findViewById<TabLayout>(R.id.tab_focus_rules)
        if (tabLayout == null) {
            pendingTabIndex = index
        } else {
            tabLayout.getTabAt(index)?.select()
        }
    }
}
