/**
 * =================================================================================
 * [Context Header]
 * * @file: AppWindowAdapter.kt
 * * @role: UI
 * * @description: 应用时间窗规则列表适配器。
 * * @responsibilities:
 * 1. 展示时间窗规则与状态。
 * 2. 触发规则编辑/撤销等回调。
 * * @context:
 * - 上游: AppWindowFragment
 * - 下游: RecyclerView
 * * @constraints (必读):
 * 1. 不执行复杂业务判定。
 * 2. 仅负责 UI 绑定。
 * 3. 保持空值安全。
 * =================================================================================
 */
package com.ironwill.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ironwill.app.R
import com.ironwill.app.manager.AppWindowManager



class AppWindowAdapter(
    private val context: Context,
    private var rules: MutableList<AppWindowManager.AppWindowRuleWithStatus>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onUndo: (Int) -> Unit
) : RecyclerView.Adapter<AppWindowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvDays: TextView = view.findViewById(R.id.tvDays)
        val llAppIcons: LinearLayout = view.findViewById(R.id.llAppIcons)
        val layoutAppPreview: View = view.findViewById(R.id.layoutAppPreview)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val switchEnable: Switch = view.findViewById(R.id.switchEnable)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_window_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ruleWithStatus = rules[position]
        val rule = ruleWithStatus.rule
        val status = ruleWithStatus.status
        
        holder.tvName.text = rule.name
        holder.tvTime.text = rule.getSummary()
        holder.tvDays.text = formatDays(rule.daysOfWeek)
        
        holder.switchEnable.setOnCheckedChangeListener(null) // Reset listener
        holder.switchEnable.isChecked = rule.isEnabled
        
        // --- Status & Badge Logic ---
        // --- Status & Badge Logic ---
        if (status != AppWindowManager.RuleStatus.ACTIVE) {
            holder.tvStatusBadge.visibility = View.VISIBLE
            holder.tvStatusBadge.text = when(status) {
                AppWindowManager.RuleStatus.PENDING_MODIFICATION -> "待生效: 修改"
                AppWindowManager.RuleStatus.PENDING_DELETION -> "待生效: 删除"
                AppWindowManager.RuleStatus.PENDING_ADDITION -> "待生效: 新增"
                else -> ""
            }
            
            // 🚀 [UI] Reverted "Effective Time" display per user request.
            // User found it confusing/unnecessary.
            
            // 🚀 [Rule] 
            // 1. Pending Deletion -> Show UNDO (Revert).
            // 2. Pending Modification -> Show DELETE (Schedule Deletion).
            // 3. Pending Addition -> Show DELETE (Cancel Addition, effectively Undo).
            
            holder.btnDelete.visibility = View.VISIBLE
            holder.switchEnable.isEnabled = true
            holder.itemView.alpha = 1.0f

            when (status) {
                AppWindowManager.RuleStatus.PENDING_DELETION -> {
                    // Revert mode
                    holder.btnDelete.setImageResource(android.R.drawable.ic_menu_revert)
                    holder.btnDelete.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0F7FA"))
                    holder.btnDelete.imageTintList = ColorStateList.valueOf(Color.parseColor("#006064"))
                    holder.btnDelete.setOnClickListener { onUndo(holder.adapterPosition) }
                    
                    holder.itemView.alpha = 0.6f
                    holder.switchEnable.isEnabled = false
                    holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#EF5350")) // Red
                }
                AppWindowManager.RuleStatus.PENDING_ADDITION -> {
                    // Delete mode (Cancel Addition)
                    holder.btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
                    holder.btnDelete.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFE0E0"))
                    holder.btnDelete.imageTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                    // For Addition, Deleting = Undoing the Add
                    holder.btnDelete.setOnClickListener { onUndo(holder.adapterPosition) }
                    
                    holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#66BB6A")) // Green
                }
                AppWindowManager.RuleStatus.PENDING_MODIFICATION -> {
                    // Delete mode (Schedule Deletion of the Rule)
                    holder.btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
                    holder.btnDelete.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFE0E0"))
                    holder.btnDelete.imageTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
                    
                    holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
                    
                    holder.tvStatusBadge.setBackgroundColor(Color.parseColor("#FFA726")) // Orange
                }
                else -> {
                    // Should not happen handling in else
                }
            }
        } else {
            holder.tvStatusBadge.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            holder.switchEnable.isEnabled = true
            
            // Normal Delete UI
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
            holder.btnDelete.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFE0E0"))
            holder.btnDelete.imageTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            
            holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        // --- App Icons Preview (With Names for Few Apps) ---
        holder.llAppIcons.removeAllViews()
        val maxPreview = 4
        val iconSize = dpToPx(24)
        val margin = dpToPx(4)
        
        val previewList = rule.packageNames.take(maxPreview)
        previewList.forEach { pkg ->
            // 创建水平容器（图标+名称）
            val container = LinearLayout(context)
            container.orientation = LinearLayout.HORIZONTAL
            container.gravity = android.view.Gravity.CENTER_VERTICAL
            val containerLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            containerLp.setMargins(0, 0, margin * 2, 0)
            container.layoutParams = containerLp
            
            // 图标
            val iv = android.widget.ImageView(context)
            val ivLp = LinearLayout.LayoutParams(iconSize, iconSize)
            iv.layoutParams = ivLp
            try {
                iv.setImageDrawable(context.packageManager.getApplicationIcon(pkg))
            } catch (e: Exception) {
                iv.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            container.addView(iv)
            
            // 仅当应用数<=2时显示名称（避免拥挤）
            if (rule.packageNames.size <= 2) {
                val tvName = TextView(context)
                val nameLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                nameLp.setMargins(dpToPx(4), 0, 0, 0)
                tvName.layoutParams = nameLp
                tvName.textSize = 11f
                tvName.setTextColor(Color.parseColor("#455A64"))
                tvName.maxLines = 1
                tvName.ellipsize = android.text.TextUtils.TruncateAt.END
                tvName.maxWidth = dpToPx(60)
                try {
                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                    tvName.text = context.packageManager.getApplicationLabel(appInfo)
                } catch (e: Exception) {
                    tvName.text = pkg.substringAfterLast(".")
                }
                container.addView(tvName)
            }
            
            holder.llAppIcons.addView(container)
        }

        // 🚀 [UI] Show "+N" if more apps exist
        if (rule.packageNames.size > maxPreview) {
             val remaining = rule.packageNames.size - maxPreview
             val tvMore = TextView(context)
             val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, iconSize)
             lp.setMargins(0, 0, margin, 0)
             tvMore.layoutParams = lp
             tvMore.text = "+$remaining"
             tvMore.textSize = 12f
             tvMore.setTextColor(Color.GRAY)
             tvMore.gravity = android.view.Gravity.CENTER_VERTICAL
             holder.llAppIcons.addView(tvMore)
        }
        
        // --- Click Events ---
        holder.layoutAppPreview.setOnClickListener { onEdit(holder.adapterPosition) }
        holder.itemView.setOnClickListener { onEdit(holder.adapterPosition) }
        
        holder.switchEnable.setOnCheckedChangeListener { _, isChecked -> 
             if (rule.isEnabled != isChecked) {
                 onToggle(holder.adapterPosition, isChecked) 
             }
        }
    }

    override fun getItemCount() = rules.size

    fun updateData(newRules: List<AppWindowManager.AppWindowRuleWithStatus>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun formatDays(days: Set<Int>): String {
        if (days.size == 7) return "每天"
        if (days.containsAll(listOf(1, 2, 3, 4, 5)) && days.size == 5) return "工作日"
        if (days.containsAll(listOf(6, 7)) && days.size == 2) return "周末"
        
        val dayMap = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
        val dayList = days.sorted().map { dayMap[it] ?: "" }
        return "周" + dayList.joinToString("、")
    }
}
