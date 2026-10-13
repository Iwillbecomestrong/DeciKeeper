/**
 * =================================================================================
 * [Context Header]
 * * @file: PendingChangesDialog.kt
 * * @role: UI
 * * @description: 「延迟生效表」弹窗：勿扰时段、应用时间窗、规则参数、全局黑名单的待生效修改统一列出，
 *   每一项右侧有独立的撤销按钮。三个时间规则页的「延迟生效修改」卡片共用它。
 * * @responsibilities:
 * 1. 展示 [PendingChangeTable] 枚举的全部待生效项（含统一延后下连加严也被延后的项），按模块分组。
 * 2. 逐项撤销并刷新列表；设置锁定时段内只读。
 * * @context:
 * - 上游: TimeConfigFragment, AppWindowFragment, GlobalParamsFragment, AppSelectionActivity
 * - 下游: PendingChangeTable
 * * @constraints (必读):
 * 1. 颜色一律走主题 Token，不硬编码。
 * 2. 业务判断全部在 PendingChangeTable，这里不写规则。
 * =================================================================================
 */
package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.decikeeper.app.R
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import com.decikeeper.app.manager.PendingChangeTable
import com.decikeeper.app.manager.PendingChangeTable.Direction
import com.decikeeper.app.manager.PendingChangeTable.Scope
import com.decikeeper.app.manager.TimeRuleEditWindowPolicyManager
import com.decikeeper.app.manager.TimeRuleManager
import com.decikeeper.app.service.MonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PendingChangesDialog {

    /**
     * @param focus 当前所在页对应的模块，分组时排在最前
     * @param onChanged 每撤销一项后回调，调用方据此刷新自己页面
     */
    fun show(context: Context, focus: Scope?, onChanged: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pending_changes, null)
        val tvSummary = view.findViewById<TextView>(R.id.tv_pending_dialog_summary)
        val container = view.findViewById<LinearLayout>(R.id.container_pending_items)

        val dialog = AlertDialog.Builder(context)
            .setTitle("延迟生效修改")
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        fun render() {
            container.removeAllViews()
            val items = PendingChangeTable.items(context)
            tvSummary.text = summaryOf(context, items.size)
            if (items.isEmpty()) {
                container.addView(TextView(context).apply {
                    text = "当前没有待生效的修改"
                    setTextColor(ThemeManager.getColorAttr(context, R.attr.zzMuted))
                    textSize = 13f
                    setPadding(0, dp(context, 16), 0, dp(context, 16))
                })
                return
            }
            val locked = TimeRuleManager.isTimeRuleEditLocked(context)
            val scopes = Scope.entries.sortedBy { if (it == focus) 0 else 1 }
            for (scope in scopes) {
                val group = items.filter { it.scope == scope }
                if (group.isEmpty()) continue
                container.addView(TextView(context).apply {
                    text = "${scope.title}（${group.size}）"
                    setTextColor(ThemeManager.getColorAttr(context, R.attr.zzText))
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(context, 12), 0, 0)
                })
                for (item in group) {
                    container.addView(bindRow(context, container, item, locked) {
                        val ok = PendingChangeTable.revoke(context, item)
                        Toast.makeText(
                            context,
                            if (ok) "已撤销：${item.title}" else "该项已不在待生效列表中",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) sendRefresh(context)
                        onChanged()
                        render()
                    })
                }
            }
        }

        render()
        dialog.show()
    }

    private fun bindRow(
        context: Context,
        parent: LinearLayout,
        item: PendingChangeTable.Item,
        locked: Boolean,
        onRevoke: () -> Unit
    ): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_pending_change, parent, false)
        val badge = row.findViewById<TextView>(R.id.tv_pending_item_badge)
        val (badgeText, badgeAttr) = when (item.direction) {
            Direction.STRICTER -> "加严" to R.attr.zzSuccess
            Direction.LOOSER -> "放宽" to R.attr.zzWarning
            Direction.NEUTRAL -> "调整" to R.attr.zzAccent
        }
        badge.text = badgeText
        badge.backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(context, badgeAttr))

        row.findViewById<TextView>(R.id.tv_pending_item_title).text = item.title
        row.findViewById<TextView>(R.id.tv_pending_item_detail).apply {
            text = item.detail
            visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.tv_pending_item_effective).text = "⏳ ${formatTime(item.effectiveMillis)} 生效"

        val btn = row.findViewById<Button>(R.id.btn_pending_item_revoke)
        when {
            !item.revocable -> {
                btn.isEnabled = false
                btn.alpha = 0.5f
                row.findViewById<TextView>(R.id.tv_pending_item_effective).text =
                    "⏳ ${formatTime(item.effectiveMillis)} 生效 · ${item.unrevocableHint ?: "无法单独撤销"}"
            }
            locked -> {
                btn.isEnabled = false
                btn.alpha = 0.5f
            }
            else -> btn.setOnClickListener { onRevoke() }
        }
        return row
    }

    private fun summaryOf(context: Context, count: Int): String {
        val policy = EffectiveActivationPolicyManager.getPolicy(context)
        val head = if (count == 0) "没有待生效的修改。" else "共 $count 项待生效，撤销只让该项回到当前生效的设置，不影响其他项。"
        val policyLine = if (policy == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY) {
            "\n当前为「统一延后」：加严和放宽都要等到生效时刻才生效。"
        } else ""
        val lockLine = if (TimeRuleManager.isTimeRuleEditLocked(context)) {
            "\n" + TimeRuleEditWindowPolicyManager.getLockoutHintText(context)
        } else ""
        return head + policyLine + lockLine
    }

    /**
     * 各页「延迟生效修改」卡片里的摘要：该页对应模块的待生效项，按生效时刻分组。
     * 没有待生效项返回空串。
     */
    fun cardSummary(context: Context, vararg scopes: Scope): String {
        val items = scopes.flatMap { PendingChangeTable.items(context, it) }
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        items.groupBy { it.effectiveMillis }.toSortedMap().forEach { (effective, group) ->
            if (sb.isNotEmpty()) sb.append("\n")
            group.forEach { item ->
                val tag = when (item.direction) {
                    Direction.STRICTER -> "（加严）"
                    Direction.LOOSER -> "（放宽）"
                    Direction.NEUTRAL -> ""
                }
                sb.append("• ").append(item.title).append(tag).append('\n')
                item.detail.lines().filter { it.isNotBlank() }.forEach { sb.append("   ").append(it.trim()).append('\n') }
            }
            sb.append("📅 生效时间: ").append(formatTime(effective)).append('\n')
        }
        sb.append("点击查看全部，可逐项撤销")
        return sb.toString()
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun sendRefresh(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(MonitorService.ACTION_REFRESH_RULES).setPackage(context.packageName))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
