/**
 * 文件路径: app/src/main/java/com/ironwill/app/ui/TimeSegmentAdapter.kt
 * 文件说明: 时间段列表适配器（类似应用时间窗的摘要卡片+点击弹窗编辑模式）
 */
package com.zhizhi.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zhizhi.app.R
import com.zhizhi.app.data.TimeSegment
import com.zhizhi.app.data.formatUsableCountHalfUnits




class TimeSegmentAdapter(
    private val segments: MutableList<TimeSegment>,
    var isReadOnly: Boolean = false,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<TimeSegmentAdapter.ViewHolder>() {

    private val newSegmentSet = mutableSetOf<TimeSegment>()
    private val pendingDeletionSet = mutableSetOf<TimeSegment>()
    private val modifiedSegmentSet = mutableSetOf<TimeSegment>()

    fun markAsNew(segment: TimeSegment) { newSegmentSet.add(segment) }
    fun isNewSegment(segment: TimeSegment): Boolean = newSegmentSet.contains(segment)
    fun markAsModified(segment: TimeSegment) { modifiedSegmentSet.add(segment) }
    fun isModifiedSegment(segment: TimeSegment): Boolean = modifiedSegmentSet.contains(segment)

    fun setPendingDeletions(segments: List<TimeSegment>) {
        pendingDeletionSet.clear()
        pendingDeletionSet.addAll(segments)
        notifyDataSetChanged()
    }
    fun isPendingDeletion(segment: TimeSegment): Boolean = pendingDeletionSet.contains(segment)
    fun markPendingDeletion(segment: TimeSegment) { pendingDeletionSet.add(segment) }
    fun unmarkPendingDeletion(segment: TimeSegment) { pendingDeletionSet.remove(segment) }
    fun getPendingDeletions(): Set<TimeSegment> = pendingDeletionSet.toSet()
    fun getNewSegments(): Set<TimeSegment> = newSegmentSet.toSet()

    fun resetStates() {
        newSegmentSet.clear()
        modifiedSegmentSet.clear()
        pendingDeletionSet.clear()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardSegment: androidx.cardview.widget.CardView = view.findViewById(R.id.card_segment)
        val tvTitle: TextView = view.findViewById(R.id.tv_segment_title)
        val tvName: TextView = view.findViewById(R.id.tv_segment_name)
        val tvTime: TextView = view.findViewById(R.id.tv_segment_time)
        val tvDays: TextView = view.findViewById(R.id.tv_segment_days)
        val tvLimitInfo: TextView = view.findViewById(R.id.tv_segment_limit_info)
        val tvListSummary: TextView = view.findViewById(R.id.tv_segment_list_summary)
        val switchEnable: Switch = view.findViewById(R.id.switch_segment_enable)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_segment)
        val tvNewBadge: TextView = view.findViewById(R.id.tv_new_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_time_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val segment = segments[position]

        val circleNumbers = arrayOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
        holder.tvTitle.text = if (position < circleNumbers.size) circleNumbers[position] else "${position + 1}"

        holder.tvName.text = segment.name
        holder.tvTime.text = String.format("%02d:%02d-%02d:%02d",
            segment.startHour, segment.startMinute, segment.endHour, segment.endMinute)

        // Format days
        val days = segment.daysOfWeek
        if (days == null || days.size == 7) {
            holder.tvDays.text = "每天"
        } else {
            val dayMap = mapOf(1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六")
            val dayStr = days.sorted().map { dayMap[it] ?: "" }.joinToString("")
            holder.tvDays.text = "周$dayStr"
        }

        // Format limit info（第二行只放休息/次数，与应用时间窗一致）
        val effectiveCount = formatUsableCountHalfUnits(segment.getEffectiveUsableCountLimitHalfUnits())
        holder.tvLimitInfo.text = "休息 ${segment.limitMins}min | ${effectiveCount}次"

        // 第三行：本时段黑名单摘要（模式感知：白名单显示"白名单 N 应用"）
        holder.tvListSummary.text = TimeConfigFragment.segmentListSummary(holder.itemView.context, segment)

        // Switch
        val isEnabled = segment.isEnabled && !isPendingDeletion(segment)
        holder.switchEnable.setOnCheckedChangeListener(null)
        holder.switchEnable.isChecked = isEnabled
        holder.switchEnable.isEnabled = !isReadOnly && !isPendingDeletion(segment)

        val alpha = if (!isEnabled || isPendingDeletion(segment)) 0.5f else 1.0f
        holder.itemView.alpha = alpha

        // Badge & Card Background
        val context = holder.itemView.context
        val surfaceColor = ThemeManager.getColorAttr(context, R.attr.zzSurface)
        val warningColor = ThemeManager.getColorAttr(context, R.attr.zzWarning)
        val warningSoft = ThemeManager.getColorAttr(context, R.attr.zzWarningSoft)
        val dangerColor = ThemeManager.getColorAttr(context, R.attr.zzDanger)
        val dangerSoft = ThemeManager.getColorAttr(context, R.attr.zzDangerSoft)
        val successColor = ThemeManager.getColorAttr(context, R.attr.zzSuccess)
        val successSoft = ThemeManager.getColorAttr(context, R.attr.zzSuccessSoft)

        val isPendingDel = isPendingDeletion(segment)
        if (isReadOnly) {
            holder.cardSegment.setCardBackgroundColor(ThemeManager.getColorAttr(context, R.attr.zzSurface2))
            holder.tvNewBadge.visibility = View.GONE
        } else if (isPendingDel) {
            holder.cardSegment.setCardBackgroundColor(dangerSoft)
            holder.tvNewBadge.text = "待生效: 删除"
            holder.tvNewBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(dangerColor)
            holder.tvNewBadge.visibility = View.VISIBLE
        } else if (isNewSegment(segment)) {
            holder.cardSegment.setCardBackgroundColor(successSoft)
            holder.tvNewBadge.text = "待生效: 新增"
            holder.tvNewBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
            holder.tvNewBadge.visibility = View.VISIBLE
        } else if (isModifiedSegment(segment)) {
            holder.cardSegment.setCardBackgroundColor(warningSoft)
            holder.tvNewBadge.text = "待生效: 修改"
            holder.tvNewBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
            holder.tvNewBadge.visibility = View.VISIBLE
        } else {
            holder.cardSegment.setCardBackgroundColor(surfaceColor)
            holder.tvNewBadge.visibility = View.GONE
        }

        // Click listeners
        holder.cardSegment.setOnClickListener {
            if (!isReadOnly && !isPendingDel) onEdit(holder.adapterPosition)
        }

        holder.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onToggle(pos, isChecked)
            }
        }

        // Delete button
        if (isPendingDel) {
            holder.btnDelete.setImageResource(android.R.drawable.ic_menu_revert)
            holder.btnDelete.setColorFilter(Color.parseColor("#1565C0"))
            holder.btnDelete.setOnClickListener {
                onDelete(holder.adapterPosition) // This will undo
            }
        } else {
            holder.btnDelete.setImageResource(android.R.drawable.ic_menu_delete)
            holder.btnDelete.setColorFilter(Color.parseColor("#D32F2F"))
            holder.btnDelete.visibility = if (isReadOnly) View.GONE else View.VISIBLE
            holder.btnDelete.setOnClickListener {
                onDelete(holder.adapterPosition)
            }
        }
    }

    override fun getItemCount() = segments.size

    private fun formatDays(days: Set<Int>?): String {
        if (days == null || days.size == 7) return "每天"
        val dayMap = mapOf(1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六")
        return "周" + days.sorted().map { dayMap[it] ?: "" }.joinToString("")
    }
}
