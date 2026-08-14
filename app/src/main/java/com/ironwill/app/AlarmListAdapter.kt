/**
 * =================================================================================
 * [Context Header]
 * * @file: AlarmListAdapter.kt
 * * @role: UI
 * * @description: Adapter for alarm list.
 * * @responsibilities:
 * 1. Bind alarm time/repeat info.
 * 2. Handle enable/edit/delete callbacks.
 * * @context:
 * - 上游: AlarmFragment
 * - 下游: AlarmScheduler
 * * @constraints (必读):
 * 1. 禁止承载复杂业务逻辑。
 * =================================================================================
 */
package com.ironwill.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class AlarmListAdapter(
    private val items: MutableList<AlarmScheduler.AlarmEntry>,
    private val onToggle: (AlarmScheduler.AlarmEntry, Boolean) -> Unit,
    private val onEdit: (AlarmScheduler.AlarmEntry) -> Unit,
    private val onDelete: (AlarmScheduler.AlarmEntry) -> Unit,
    private val onChallenge: (AlarmScheduler.AlarmEntry) -> Unit,
    private val onSystemBackupClick: (AlarmScheduler.AlarmEntry) -> Unit,
    private val onSystemBackupHelp: () -> Unit
) : RecyclerView.Adapter<AlarmListAdapter.AlarmViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
        return AlarmViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, onToggle, onEdit, onDelete, onChallenge, onSystemBackupClick, onSystemBackupHelp)
    }

    override fun getItemCount(): Int = items.size

    fun updateAll(newItems: List<AlarmScheduler.AlarmEntry>) {
        if (items !== newItems) {
            items.clear()
            items.addAll(newItems)
        }
        notifyDataSetChanged()
    }

    class AlarmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tv_alarm_time)
        private val tvDays: TextView = itemView.findViewById(R.id.tv_alarm_days)
        private val switchEnabled: Switch = itemView.findViewById(R.id.switch_alarm_item)
        private val cardAlarmItem: CardView = itemView.findViewById(R.id.card_alarm_item)
        private val btnDelete: View = itemView.findViewById(R.id.btn_delete_alarm)
        private val btnSystemBackupHelp: ImageButton = itemView.findViewById(R.id.btn_system_backup_help)
        private val btnSystemBackupAction: Button = itemView.findViewById(R.id.btn_system_backup_action)
        private val btnChallenge: Button = itemView.findViewById(R.id.btn_challenge_alarm)

        fun bind(
            item: AlarmScheduler.AlarmEntry,
            onToggle: (AlarmScheduler.AlarmEntry, Boolean) -> Unit,
            onEdit: (AlarmScheduler.AlarmEntry) -> Unit,
            onDelete: (AlarmScheduler.AlarmEntry) -> Unit,
            onChallenge: (AlarmScheduler.AlarmEntry) -> Unit,
            onSystemBackupClick: (AlarmScheduler.AlarmEntry) -> Unit,
            onSystemBackupHelp: () -> Unit
        ) {
            tvTime.text = String.format(Locale.getDefault(), "%02d:%02d", item.hour, item.minute)
            tvDays.text = formatDays(item.days)

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = item.enabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }

            btnSystemBackupAction.setOnClickListener {
                onSystemBackupClick(item)
            }
            btnSystemBackupHelp.setOnClickListener { onSystemBackupHelp() }

            // 点击整个卡片任意位置即可编辑（如同应用时间窗列表）
            cardAlarmItem.setOnClickListener { onEdit(item) }
            // 右上角删除按钮
            btnDelete.setOnClickListener { onDelete(item) }
            // [DO NOT DELETE] 挑战入口按钮：仅当闹钟正在震动时才显示
            // 这是防卡死的兜底机制，不是普通功能按钮（关联 Issue_Tracker 05-13）
            val activeAlarmId = AlarmScheduler.getActiveAlarmId(itemView.context)
            val isThisAlarmActive = item.enabled && activeAlarmId == item.id
            if (isThisAlarmActive) {
                btnChallenge.visibility = View.VISIBLE
                btnChallenge.setOnClickListener { onChallenge(item) }
            } else {
                btnChallenge.visibility = View.GONE
                btnChallenge.setOnClickListener(null)
            }
        }

        private fun formatDays(days: BooleanArray): String {
            val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            if (days.isEmpty() || days.none { it }) return "每天"
            val selected = labels.filterIndexed { index, _ -> index < days.size && days[index] }
            return if (selected.isEmpty()) "每天" else selected.joinToString(" ")
        }
    }
}
