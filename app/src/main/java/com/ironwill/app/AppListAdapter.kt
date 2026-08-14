/**
 * 文件路径: app/src/main/java/com/ironwill/app/AppListAdapter.kt
 * 文件说明: 应用列表适配器，用于在 ListView 中展示应用列表（包括图标、名称、选中状态）。
 * 主要功能:
 * - 适配应用数据到 ListView 的每一项。
 * - 处理每个 Item 的显示逻辑 (区分适应期、新增应用、待删除应用等不同状态的颜色显示)。
 * - 处理 CheckBox 的勾选事件并回调。
 *
 * 主要函数:
 * - getView(position, convertView, parent): 核心方法，绘制每一行的视图。
 *   - 根据 position 获取对应的 AppInfo。
 *   - 设置图标、名称。
 *   - 根据当前是否处于“适应期”或“已有/新增/待删”状态，设置不同的背景色和文字颜色。
 *   - 绑定 CheckBox 监听器，并通过 listener 回调勾选状态变化。
 */
/**
 * =================================================================================
 * [Context Header]
 * * @file: AppListAdapter.kt
 * * @role: UI
 * * @description: 应用列表展示的 RecyclerView 适配器。
 * * @responsibilities:
 * 1. 绑定应用信息到列表项。
 * 2. 处理列表交互回调。
 * * @context:
 * - 上游: AppSelectionActivity
 * - 下游: RecyclerView
 * * @constraints (必读):
 * 1. 不执行耗时操作。
 * 2. 仅负责 UI 绑定。
 * 3. 保持空值安全。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView

// ❌ 删除了 data class AppInfo ... (因为 AppSelectionActivity 里已经有了)

class AppListAdapter(
    private val context: Context,
    private val dataSource: List<AppInfo>,
    private val pendingApps: Set<String>? = null,  // 待生效的黑名单
    private val originalSelection: Set<String>,    // 🚀 [Fix] Decoupled from CreditManager logic
    private val listener: (AppInfo, Boolean) -> Unit,
    private val isReadOnly: Boolean = false
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun getCount(): Int {
        return dataSource.size
    }

    override fun getItem(position: Int): Any {
        return dataSource[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: ViewHolder

        if (convertView == null) {
            // 引用 item_app_list.xml
            view = inflater.inflate(R.layout.item_app_list, parent, false)
            holder = ViewHolder(
                view.findViewById(R.id.img_app_icon),
                view.findViewById(R.id.tv_app_name),
                view.findViewById(R.id.cb_app_select)
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        val appInfo = dataSource[position]

        holder.appIcon.setImageDrawable(appInfo.icon)

        // 获取当前状态
        val isAdaptation = CreditManager.isInAdaptationPeriod(context)
        // 🚀 [Fix] Use passed original set, ensuring this works for App Window too!
        val isCurrent = originalSelection.contains(appInfo.packageName)
        val isSelected = appInfo.isSelected  // 当前勾选状态
        
        if (isAdaptation) {
            // 适应期：简单显示勾选或不勾选，不区分明日生效
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.appName.text = appInfo.name
            if (isSelected) {
                holder.appName.setTextColor(android.graphics.Color.parseColor("#006064")) // 适应期深青色
            } else {
                holder.appName.setTextColor(android.graphics.Color.parseColor("#757575"))
            }
        } else {
            // 正常期：判断状态
            val isOriginal = isCurrent && isSelected  // 原有应用
            val isNewPending = !isCurrent && isSelected  // 新增待生效
            val isPendingRemoval = isCurrent && !isSelected  // 待删除
            
            when {
                isNewPending -> {
                    view.setBackgroundColor(android.graphics.Color.parseColor("#E1F5FE"))
                    holder.appName.text = appInfo.name
                    holder.appName.setTextColor(android.graphics.Color.parseColor("#0288D1"))
                }
                isPendingRemoval -> {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // 🚀 [Fix] User Request: Remove "❌ " prefix. Keep Red color for feedback.
                    holder.appName.text = appInfo.name 
                    holder.appName.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
                }
                isOriginal -> {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    holder.appName.text = appInfo.name
                    holder.appName.setTextColor(android.graphics.Color.parseColor("#1565C0"))
                }
                else -> {
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    holder.appName.text = appInfo.name
                    holder.appName.setTextColor(android.graphics.Color.parseColor("#757575"))
                }
            }
        }

        // 移除旧监听
        holder.checkBox.setOnCheckedChangeListener(null)

        holder.checkBox.isChecked = appInfo.isSelected
        holder.checkBox.isEnabled = !isReadOnly

        // 添加新监听
        if (!isReadOnly) {
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                appInfo.isSelected = isChecked
                listener(appInfo, isChecked)
            }
        }

        // 点击整行也能触发勾选
        view.setOnClickListener {
            if (!isReadOnly) {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }

        return view
    }

    private class ViewHolder(
        val appIcon: ImageView,
        val appName: TextView,
        val checkBox: CheckBox
    )
}