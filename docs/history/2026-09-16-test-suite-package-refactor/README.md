# 历史归档：测试套件分包重构与工程治理 (2026-09-16)

## 文档性质声明
> **[IMPORTANT]**
> 本目录下的所有文档（`task-plan.md`、`completion-report.md`、`notes.md`）均为本次重构任务执行期间的过程工作记录与阶段快照，具有**历史快照性质（Historical / Completed），不具备项目现行规范约束力（Non-normative）**。
>
> 项目意图真相与唯一设计规格请严格参阅根目录单真理源：
> - 协作规范与设计红线：[`AGENT_CORE.md`](../../../AGENT_CORE.md)
> - 意图真相与架构规范：[`SPEC.md`](../../../SPEC.md)
> - 历史决策与防回归记录：[`HISTORY.md`](../../../HISTORY.md)

## 本次重构背景与标识
- **基线提交 (Base Commit)**: `3e9a0a1b5326f74afc746a6e887c46df1c564d08`
- **目标提交 (Target Commit)**: `333e385637621e3add4382541798b2c83337f8c2`
- **执行时间**: 2026-09-16
- **核心成果**:
  1. 将扁平堆叠在 `app/src/test/java/com/decikeeper/app/` 的 167+ 个单元测试按业务领域划分为 10 个独立包（`manager`, `ui`, `protection`, `data`, `service`, `questionbank`, `oem`, `governance`, `testutil`, `statistics`）；
  2. 修复 `AboutActivityLongVersionCodeTest.kt` 语法错误并补齐子包资源引用；
  3. GitHub Actions CI 补充 `dev` 分支触发监听并固化 Gradle JVM 测试时区为 `Asia/Shanghai`。
