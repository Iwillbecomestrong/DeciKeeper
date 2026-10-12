# 次数结余切段复活修复

## 目标与范围

修复 2026-10-12 真实流水中「下午次数归零，晚上发放 3 次却得到 4.5 次」。只调整次数扣费归属，不改时长、冷却、借用倍率，不回填历史数据。

- 工作位置：`C:/Users/23248/.codex/worktrees/quota-ledger-gap/IronWill-OpenSource`
- 分支：`codex/quota-ledger-gap`，基于两端最新 dev `94bf60b5`。
- 涉及：`CreditManager.kt`、`LockWindowManager.kt`、`UsableCountCarryConsumptionTest.kt`、`docs/specs/focus.md`、`HISTORY.md`。

## 验收

1. 上午余 1.5 次，下午消耗 4.5 次后，两段各自用量均到上限；进入晚上只剩新发放的 3 次。
2. 普通加权扣费与借用事务都使用今日有效次数和真实前序结余，不扣停用段。
3. 借用事务当前额度不足时拒绝，不能留下部分次数扣费。
4. 先在原始 dev 验证回归测试失败，再在修复后验证通过；运行全量 JVM 测试及签名 Release 构建。

## 验证状态

- 原始 dev 的 `CreditManager.kt` 上，新增 5 项回归测试中 3 项失败；修复后 5 项全部通过，无跳过。
- `:app:testDebugUnitTest --max-workers=4 --no-configuration-cache`：1863 项，1851 通过、1 失败、11 跳过。唯一失败为 `DailySegmentQuotaLedgerTest.enablingASegmentInProgressProratesByTheRemainingTimeAndCarries`，第 140 行的时长折算断言；还原 dev 原始 `CreditManager.kt` 后单独复跑同样失败，确认与本次次数修改无关，不扩大范围修复。复核结束后已按 SHA-256 确认修复源码原样恢复。
- `:app:assembleRelease --max-workers=4 --no-configuration-cache`：成功（2m9s）；`verify_release_artifact.ps1 -RequireSigned`：签名、包名和权限核验通过。
- APK：`app/build/outputs/apk/release/app-release.apk`；SHA-256：`545545f55144dba3c0bb875d72c8cbe64370a8606dcc8a8881d317ae4be7443d`。
- 原始全量测试 XML 已保留于 `app/build/verification/quota-full-test-results`，构建签名证据于 `app/build/verification/quota-release/release-manifest.json`；RED/GREEN、全量、Release 与基线复核日志分别为 worktree 中的 `quota-red.log`、`quota-green.log`、`quota-full-tests.log`、`quota-release.log`、`quota-baseline-check.log`（均为忽略的本地运行资产）。
- 尚未真机安装或复测；旧版本已经产生的历史差额不回填。AOCI 工具未向当前会话提供，无法完成正式索引维护。全量测试仍非全绿，不宣告全仓验证通过。

## 合回 dev 的候选验证（2026-10-12）

- 已刷新两端远端 dev，仍为 `94bf60b5`；本地 dev 为 `47ea672f`，已有进阶保护改动，完整保留。
- 修复提交 `7146f96`；同步本地 dev 的合并提交 `4ff7ee9`。仅 `HISTORY.md` 有新增位置冲突，两项决策均保留，本次次数修复改为 `DECISION-51`，既有进阶保护保持 `DECISION-50`。
- 合并候选重新运行全量 JVM 测试：1917 项，1905 通过、11 跳过，唯一失败仍为原有时长折算用例；新增次数回归 5 项全部通过。日志：`quota-merged-tests.log`。
- 合并候选签名 Release 构建成功（2m5s），包名、签名及权限核验通过；日志：`quota-merged-release.log`，证据：`app/build/verification/quota-merged-release/release-manifest.json`。
- 最新 APK 仍在 `app/build/outputs/apk/release/app-release.apk`，已替换首次构建产物；最新 SHA-256：`b25a5943e9c0d7b0243882146bc9863bcf74dc2137ca6957362637cf257075ea`。
- 候选相对本地 dev 仅本次修复的 6 个文件有差异；最终本地 dev 的合并提交以 Git 为准。真机、历史数据和 AOCI 验证边界仍同上。
