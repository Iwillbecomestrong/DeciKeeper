# 六项专项计划完成报告

> 本文件在实施与验证完成后填写。所有结论以本机命令、源码、测试、构建产物和明确的设备/授权证据为准。

## 已完成

- 设备管理器：`AntiUninstallManager` 提供纯状态映射；`RulesFragment` 以 `DevicePolicyManager.isAdminActive` 为系统事实，覆盖 vivo/iQOO 已激活仍显示、`ACTIVE/PENDING/DISABLED` 和到期可解除状态；增加 JVM 矩阵测试。
- 备份恢复：自动备份区分公共/内部双写结果；保留当前/上一版快照、旧 `ZhiZhi` 文件名和手动导入；偏好条目先验证后写入，增加损坏当前快照回退上一版与不污染既有配置测试。
- 权限/资产/依赖：最终 Release APK 已验证不含 `INTERNET`、`ACCESS_NETWORK_STATE`；更新第三方版本声明、ML Kit 依赖边界和数据资产范围说明。主数据为考研词汇、考研真题、AI 生成对联及项目生成翻译/释义，商业解析字段已移除。
- 质量门禁：启用 Release lint error 阻断；修复本轮暴露的未基线化 error；增加规范化工作区摘要的 Windows ASCII 测试路径；增加 Markdown 本地链接检查并接入 CI。
- Release：增加 `scripts/verify_release_artifact.ps1` 和 tag/手动 workflow；生成 Release manifest 与 SHA-256，正式 `-RequireSigned` 校验通过。
- 文档与边界：同步用户权限、保护规格、贡献指南、发布清单、App README、第三方声明和六份专项状态；只提取两个有测试收益的边界，没有进行大范围重构。
- 小浮窗出口倒计时：复用现有守护浮窗，加入特权/临时解锁/休息的优先级倒计时；折叠态仅显示纯数字，超过 1 小时显示 `>1h`，点击后向内展开只读明细；无有效截止时间恢复盾牌；下一次闹钟倒计时仍留在闹钟挑战界面。

## 未完成或需用户动作

- 没有可用的 vivo/OPPO 专用设备证据，设备管理器系统授权页、Launcher 卸载效果、无障碍重连空档未标记为真机通过。
- 未在专用测试设备执行公共 Documents 落盘、卸载重装自动恢复、权限缺失、Android 云备份/换机迁移验证；因此用户文档明确这些边界。
- `git ls-remote --heads github/gitee` 本次命令 exit=0 但没有返回分支 refs；不能据此证明双端已同步。未执行提交、tag、push、Release 上传或双渠道下载哈希核对。
- `docs/DATA_SOURCES.md` 已不再把项目写成“不能开源”：源码、AI 生成对联、项目生成翻译/释义和清洗结果可公开；真题随包发布保留来源说明。`duilian_single.json` 已按用户确认纳入正式开源数据范围。
- AOCI 工具未在当前工具集中暴露，未声称完成索引同步。

## 验证证据

- `python scripts/verify_markdown_links.py`：通过，89 个 Markdown 文件。
- `.\gradlew.bat --console=plain :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon '-Duser.timezone=Asia/Shanghai'`：成功；1357 tests，0 failures，0 errors，11 skipped。
- `scripts/verify_release_artifact.ps1`：小浮窗倒计时改动后重新执行 `assembleRelease`，并通过 `-RequireSigned` 校验；APK version `0.3.0`/code `2`、arm64-v8a、大小 14,435,795 bytes、SHA-256 `fa3486178bf4a5cf2efe8fa6c444906b9021c82da7f17ed2e352556e4d5af55f`；报告位于 `app/build/release-verification-signed/`。
- Release runtime 依赖解析确认 ML Kit 17.3.0 及其 `play-services-*`、`com.google.android.datatransport` 传递依赖；未因该事实擅自移除本地扫码链。
- 小浮窗专项：`GuardFloatingCountdownTest` 与 `GuardFloatingWindowManagerTest` 通过；`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，Debug lint 的剩余报告属于既有代码/既有 baseline 范围，未新增未基线化门禁失败。
- 小浮窗专项尚未做真机覆盖层视觉验收；未提交、未推送、未安装到设备。
- 全程保留用户已有 dirty worktree，未执行 reset/clean/commit/push。
