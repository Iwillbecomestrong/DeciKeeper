# Task Plan: 完成六项专项计划

## Goal
在保留现有未提交改动的前提下，依据 `docs/plans/` 六份专项计划完成可由源码与本机验证的实现、测试和发布证据；真实设备/OEM与外部授权缺口明确记录，不伪造通过结论。

## Phases
- [x] Phase 1: 阅读仓库契约、六份计划并建立基线
- [x] Phase 2: 设备管理器与 OEM 状态链（源码/JVM）
- [x] Phase 3: 备份与恢复验证及兼容性（源码/JVM）
- [x] Phase 4: 权限、依赖与数据资产审计（APK/文档边界）
- [x] Phase 5: 质量门禁与 Windows 测试隔离
- [x] Phase 6: Release 发布溯源与构建验证
- [x] Phase 7: 贡献文档与模块边界
- [x] Phase 8: 总体验收、维护认知索引并交付（AOCI 工具不可用，保留明确缺口）

## Key Questions
1. 六份计划中哪些是源码缺口，哪些只能依赖用户设备/授权/签名环境？
2. 现有工作区改动哪些属于用户已有工作，如何避免覆盖或混入？
3. 每项变更的最小测试、构建和证据是什么？

## Decisions Made
- 保留当前 `dev` 工作区及所有既有未提交改动，不清理、不重置、不提交用户未授权的内容。
- 先读仓库规范和计划，再按依赖顺序实施；跨文件业务变更优先补回归测试。
- 需要真实 OEM 操作、第三方授权确认或发布签名选择时，记录为明确阻塞并向用户询问。

## Errors Encountered
- PowerShell 未将 `-Duser.timezone=Asia/Shanghai` 作为 Gradle JVM 参数传递，Gradle 将其拆成不存在的任务 `.timezone=Asia/Shanghai`；改用带引号的参数重跑。
- 首次设备管理器状态映射编译失败：颜色分支仍引用旧的 `State`，已改为引用 `DeviceAdminUiStatus`。

## Status
**Complete for source/local scope; external verification remains** - 源码、JVM、Release、APK 权限/签名/哈希和文档门禁已完成；真机 OEM、卸载重装、系统云迁移、远端双渠道和数据授权证据仍待外部动作。

## Phase 9: 小浮窗出口倒计时（2026-09-16）

### Goal
在现有守护小浮窗中增加休息、临时解锁、特权三个出口的只读结束倒计时；不改变闹钟挑战界面的下一次响铃倒计时职责。

### Confirmed decisions
- 小浮窗是否存在仍由设置中的小浮窗保护开关控制。
- 无出口倒计时时显示原盾牌；有倒计时时完全替换为贴边纯数字标签。
- 折叠态仅显示最高优先级出口：特权 > 临时解锁 > 休息。
- 超过 1 小时显示 `>1h`，最后 1 小时显示 `M:SS`；展开态显示所有出口的精确时间。
- 点击数字标签从屏幕边缘向内展开约 220dp 的只读面板；再次点击同一小窗收起。
- 最后一个倒计时结束或倒计时数据暂时不可用时恢复盾牌，不显示旧数字或 `00:00`。
- 游戏中保持极小贴边标签，不自动隐藏、不在浮窗直接结束或延长出口。

### Phases
- [x] 9.1 Trace existing floating-window and deadline state paths
- [x] 9.2 RED: add pure countdown projection/format tests
- [x] 9.3 GREEN: implement minimal projection and floating-window rendering
- [x] 9.4 Add projection expiry/format regression coverage; UI interaction and restart behavior remain device-bound
- [x] 9.5 Verify JVM tests, lint, and debug build

### Status
**Complete for source/local scope** - 纯倒计时投影、小窗渲染、规格文档和本机测试/ lint / debug 构建均已完成；真机覆盖层视觉、游戏遮挡和 OEM 行为仍需设备验证。
