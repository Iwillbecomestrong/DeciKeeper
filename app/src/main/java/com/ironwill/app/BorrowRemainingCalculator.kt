package com.ironwill.app

/**
 * 当前段剩余解锁次数 = limit - used + 前序段结余。
 *
 * 注意：borrow principal **不应** 加进 remaining。
 *   - 主流程 [CreditManager.getRemainingUsableCount] 也是这个公式。
 *   - borrowed principal 已经锁进当前借用会话（写入 count_borrow_* key），
 *     立刻被本次借用休息消耗掉，不会变成下一次可用的次数。
 *   - "已借" 总额 UI 单独渲染（见 LockWindowManager / HomeFragment 的
 *     `已借：时长+次数` 行），不会丢失信息。
 *
 * 🐛 [BugFix 2026-07-01] 历史上 LockWindowManager/HomeFragment 三处 inline 公式误把
 * `usableBorrowed` 加进 remaining，导致借用后 UI 显示 "1次 / 7次"，与核心
 * 剩余值（0次）不一致。本函数统一收敛，与 [CreditManager.getRemainingUsableCount] 语义对齐。
 */
internal fun computeCurrentUsableRemaining(
    usableLimit: Int,
    usableUsed: Int,
    usablePrevRemaining: Int
): Int = (usableLimit - usableUsed + usablePrevRemaining).coerceAtLeast(0)