/**
 * =================================================================================
 * [Context Header]
 * * @file: AdverseAnalysis.kt
 * * @role: Domain Skill
 * * @description: 不利变更分析的数据结构与结果描述。
 * * @responsibilities:
 * 1. 提供变更类型与描述模型。
 * 2. 支撑规则变更的判定输出。
 * * @context:
 * - 上游: TimeRuleManager, AppWindowManager
 * - 下游: UI 展示与日志
 * * @constraints (必读):
 * 1. 仅包含纯数据与判定结果。
 * 2. 不引入 Android UI 依赖。
 * 3. 保持空值安全。
 * =================================================================================
 */
package com.decikeeper.app.data

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.decikeeper.app.manager.EffectiveActivationPolicyManager
import com.decikeeper.app.manager.TimeRuleManager



sealed class AnalysisResult {
    object NoChange : AnalysisResult()
    data class AllImmediate(val messages: List<String> = emptyList()) : AnalysisResult()
    object AllDelayed : AnalysisResult() // Should rarely happen if we prioritize strictness
    data class Mixed(
        val immediateChanges: List<String>,
        val delayedChanges: List<String>,
        val restoredChanges: List<String> = emptyList(), // 🚀 [NEW] Restored to Baseline
        val nextWeekChanges: List<String> = emptyList(),
        val effectiveTime: Long,
        val nextWeekEffectiveTime: Long = 0L // 🚀 [NEW] Next Monday effective time for weekly privilege
    ) : AnalysisResult()
}

object AdverseAnalysis {

    fun showAnalysisDialog(context: Context, result: AnalysisResult, onConfirm: () -> Unit) {
        showAnalysisDialog(context, result, null, onConfirm)
    }

    fun showAnalysisDialog(
        context: Context,
        result: AnalysisResult,
        onCancel: (() -> Unit)?,
        onConfirm: () -> Unit
    ) {
        // 🚀 [Adaptation Period] During Adaptation, ALL changes are immediate. Skip analysis.
        if (TimeRuleManager.isInAdaptationPeriod(context)) {
            AlertDialog.Builder(context)
                .setTitle("✅ 设置已更新")
                .setMessage("当前处于【适应期】，所有修改均立即生效。\n\n(适应期结束后，放松规则的修改将延迟到次日生效)")
                .setPositiveButton("我知道了") { _, _ -> onConfirm() }
                .setCancelable(false)
                .show()
            return
        }

        if (result is AnalysisResult.NoChange) {
            // Even if no functional change, maybe show a toast? Or just finish.
            // User requested "Restored" to be "Immediate". If NoChange means "Restored completely to active state", it's fine.
            // But if "Restored to Baseline" means "Active changed, but Pending removed", we might want to say "Restored".
            // Currently NoChange means Active==New and Pending is empty.
            android.widget.Toast.makeText(context, "设置已保存 (未发生变更)", android.widget.Toast.LENGTH_SHORT).show()
            onConfirm()
            return
        }
        
        // 🚀 [Handling All Immediate / Restored]
        // If result is AllImmediate (which includes "Restored" changes as benign)
        if (result is AnalysisResult.AllImmediate) {
            if (result.messages.isEmpty()) {
                // 🚀 [Optimization] True No Change -> Toast Only
                android.widget.Toast.makeText(context, "设置已保存 (未发生变更)", android.widget.Toast.LENGTH_SHORT).show()
                onConfirm()
                return
            }

            val sb = StringBuilder()
            sb.append("✅ 立即生效 (有利/恢复):\n")
            result.messages.forEach { sb.append("• $it\n") }
            
            AlertDialog.Builder(context)
                .setTitle("设置已保存")
                .setMessage(sb.toString().trim())
                .setPositiveButton("我知道了") { _, _ -> onConfirm() }
                .setCancelable(false)
                .show()
            return
        }

        val mixed = result as? AnalysisResult.Mixed ?: return // Should handle AllDelayed as well if possible
        val isAllNextDayPolicy =
            EffectiveActivationPolicyManager.getPolicy(context) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY

        val sb = StringBuilder()
        
        // 1. Restored / Unchanged (New Priority)
        if (mixed.restoredChanges.isNotEmpty()) {
            sb.append("🔄 维持原状 (比对基准):\n")
            mixed.restoredChanges.forEach { sb.append("• $it\n") }
            sb.append("\n")
        }

        // 2. Immediate (Favorable)
        if (mixed.immediateChanges.isNotEmpty()) {
            sb.append("✅ 立即生效 (有利改动):\n")
            mixed.immediateChanges.forEach { sb.append("• $it\n") }
            sb.append("\n")
        }

        // 3. Delayed (Daily - Adverse)
        if (mixed.delayedChanges.isNotEmpty()) {
            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(mixed.effectiveTime))
            val delayedTitle = if (isAllNextDayPolicy) {
                "🌅 次日 $timeStr 生效 (当前为「统一延后」模式):\n"
            } else {
                "🌅 次日 $timeStr 生效 (不利改动):\n"
            }
            sb.append(delayedTitle)
            mixed.delayedChanges.forEach { sb.append("• $it\n") }
            sb.append("\n")
        }
        
        // 3. Delayed (Weekly - Privilege Increase)
        if (mixed.nextWeekChanges.isNotEmpty()) {
            // Use nextWeekEffectiveTime for weekly privilege (next Monday at refresh time)
            val effectiveTimeToUse = if (mixed.nextWeekEffectiveTime > 0) mixed.nextWeekEffectiveTime else mixed.effectiveTime
            val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(effectiveTimeToUse))
            sb.append("📅 下周生效 ($dateStr):\n")
            mixed.nextWeekChanges.forEach { sb.append("• $it\n") }
        }

        if (sb.isEmpty()) {
            onConfirm()
            return
        }
        
        // 🚀 [Interactive Protection] Forced Confirmation Logic
        val hasDelayed = mixed.delayedChanges.isNotEmpty() || mixed.nextWeekChanges.isNotEmpty()
        val title = if (hasDelayed && !isAllNextDayPolicy) "⚠️ 包含延后生效项" else "变更生效分析"
        
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(sb.toString().trim())
            .setCancelable(false)

        if (hasDelayed) {
            val handleCancel = {
                onCancel?.invoke()
                Toast.makeText(context, "已取消修改", Toast.LENGTH_SHORT).show()
            }
            builder.setPositiveButton("确认仍要修改") { _, _ -> onConfirm() }
            builder.setNegativeButton("取消") { _, _ -> handleCancel() }
            builder.setOnCancelListener { handleCancel() }
        } else {
             builder.setPositiveButton("确定") { _, _ -> onConfirm() }
        }
            
        builder.show()
    }
}
