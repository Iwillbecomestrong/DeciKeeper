# 闹钟扫码预览整合

## 目标与边界

基于 github/dev 30f6b02，迁入远程扫码分支的同窗口预览思路，保留当前相机恢复链。不合并远程旧版整文件，不新增超时换题，不改变挑战进度、缓冲、第二关和成功收尾。

## 涉及文件

AlarmActivity.kt、CameraXScanManager.kt、LockAlarmOverlay.kt、window_alarm_overlay.xml、AlarmScanCutoutContractTest.kt，以及 alarm scoped spec / HISTORY。

## 验收

- Overlay 内不透明 PreviewView，COMPATIBLE 模式；显示不依赖 Activity 分屏坐标。
- 未出帧、后台、中断待重绑、覆盖层脱附、退出扫码均不接受结果；保留绑定代际与会话校验。
- 受影响测试、全量 JVM 测试与 Release 构建。
- 真机：应急白名单 → 分屏 → 返回扫码；熄屏、系统弹窗、取消再进入；任意 QR、手电筒、缩放、缓冲与第二关。

## 当前状态

- 实现完成，最终扫码/熄屏/挂载/宿主/四季主题相关 35 项测试全部通过；Release 签名构建成功。
- 全量 JVM：1763 项，1 失败，11 跳过。失败为 DailySegmentQuotaLedgerTest.enablingASegmentInProgressProratesByTheRemainingTimeAndCarries（预期约 0.65，实际 1.0）；未修改的 dev 30f6b02 独立工作区复跑同项也失败，属于基线问题，本任务不修改额度逻辑。
- 全量命令：gradlew.bat --console=plain --max-workers=2 :app:testDebugUnitTest :app:assembleRelease（测试失败，因此构建未完成）。
- 最终验证命令：gradlew.bat --console=plain --max-workers=2 :app:testDebugUnitTest --tests '*AlarmScanCutoutContractTest' --tests '*AlarmScreenOffBehaviorContractTest' --tests '*AlarmOverlayAttachContractTest' --tests '*AlarmActivityExternalHostPolicyTest' --tests '*AlarmOverlayThemeContractTest' :app:assembleRelease。
- 真机分屏/系统弹窗/熄屏恢复与四季视觉审查未执行，不能据此宣称 vivo 故障已实测解决。
- AOCI 服务未连接，认知维护未执行；aoci 索引未随本次受管理源码变化更新。


## 同步最新 dev 的合并验证

- 用户批准合并 PR #56 后，dev 新增冷却规则修复至 cda3f889；任务分支合入该版本，仅 HISTORY 新决策记录发生冲突，完整保留两项意图，将扫码记录编号改为 DECISION-48。
- 同步后执行 gradlew.bat --console=plain --continue --max-workers=2 :app:testDebugUnitTest :app:assembleRelease。1769 项测试：1757 通过、11 跳过、1 项相同的既有额度测试失败；扫码测试 12/12 通过。Release 签名构建成功，联合命令因既有测试失败返回非零。
- 日志：qr-dev-merge-verification.log。没有执行真机验证或安装，AOCI 维护仍不可用。
