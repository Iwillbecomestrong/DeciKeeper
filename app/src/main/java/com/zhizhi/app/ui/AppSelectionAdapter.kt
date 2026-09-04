/**
 * =================================================================================
 * [Context Header]
 * * @file: AppSelectionAdapter.kt
 * * @role: UI
 * * @description: 应用选择列表的 RecyclerView 适配器。
 * * @responsibilities:
 * 1. 绑定应用与勾选状态。
 * 2. 响应选择变化回调。
 * * @context:
 * - 上游: AppSelectionActivity
 * - 下游: RecyclerView
 * * @constraints (必读):
 * 1. 不执行耗时操作。
 * 2. 仅负责 UI 绑定。
 * 3. 保持空值安全。
 * =================================================================================
 */
package com.zhizhi.app.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zhizhi.app.R


class AppSelectionAdapter(
    private val context: Context,
    private val allApps: List<ApplicationInfo>,
    private val initialSelection: Set<String>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>(), Filterable {

    data class AppItem(
        val info: ApplicationInfo,
        val label: String,
        val icon: Drawable,
        var isSelected: Boolean
    )

    private val fullList: List<AppItem>
    private var filteredList: List<AppItem>
    private val selectedPackages = HashSet<String>(initialSelection)

    init {
        val pm = context.packageManager
        fullList = allApps.map {
            AppItem(
                info = it,
                label = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm),
                isSelected = selectedPackages.contains(it.packageName)
            )
        }
        filteredList = fullList
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredList[position]
        holder.tvAppName.text = item.label
        holder.ivIcon.setImageDrawable(item.icon)
        
        // 🚀 Remove listener to prevent recycling bugs
        holder.itemView.setOnClickListener(null)
        
        holder.cbSelected.isChecked = item.isSelected
        
        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.cbSelected.isChecked = item.isSelected
            
            if (item.isSelected) {
                selectedPackages.add(item.info.packageName)
            } else {
                selectedPackages.remove(item.info.packageName)
            }
            onSelectionChanged(selectedPackages)
        }
    }

    override fun getItemCount() = filteredList.size

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                if (constraint.isNullOrEmpty()) {
                    results.values = fullList
                    results.count = fullList.size
                } else {
                    val query = constraint.toString().lowercase()
                    val filtered = fullList.filter { 
                        it.label.lowercase().contains(query) || 
                        it.info.packageName.lowercase().contains(query) 
                    }
                    results.values = filtered
                    results.count = filtered.size
                }
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                filteredList = results?.values as? List<AppItem> ?: emptyList()
                notifyDataSetChanged()
            }
        }
    }
}
