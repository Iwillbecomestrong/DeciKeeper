# SPEC: 联系方式完善与问题反馈范式规范 (task-feedback-paradigm)

## 1. 背景与目标 (Goal)
- 用户确认联系邮箱为 `cyz200303@163.com`。
- 用户确认官方网站为：
  - 官方网站（海外 / GitHub）：`https://github.com/Iwillbecomestrong/DeciKeeper`
  - 官方网站（国内 / Gitee）：`https://gitee.com/my_room_welcome_you/decikeeper`
- 当前应用内「设置 → 日志诊断 → 发送日志」由于代码中预设收件人为开源占位符 `support@example.com`，且缺少标准提报指引，导致用户无法正常反馈，开发者也无法获取足够的排错上下文。
- 本任务旨在：
  1. 制定并固化「问题反馈与 Issue 提报 4 要素范式」。
  2. 明确分级反馈渠道：首选 GitHub/Gitee Issues 公开追踪，私密/离线反馈使用邮箱，应用内提供日志导出取证工具。
  3. 更新合规文档、应用内用户手册、代码预设邮箱，实现文档与代码单一真相一致。

## 2. 非目标 (Non-Goals)
- 不接入任何第三方网络上报/崩溃 SDK（严格坚守决定守护 SPEC 的零第三方收集、零联网追踪原则）。
- 不在客户端内自建网络上传工单服务，继续保持本地纯净离线。

## 3. 详细设计与外部契约 (Specification)

### 3.1 渠道分级设计
- **首选公开渠道（推荐）**：
  - GitHub Issues: `https://github.com/Iwillbecomestrong/DeciKeeper/issues`
  - Gitee Issues: `https://gitee.com/my_room_welcome_you/decikeeper/issues`
  - 适用：Bug 反馈、功能建议、使用疑问，公开透明且便于版本追踪与避免重复提报。
- **官方网站与直接联系渠道**：
  - 联系邮箱：`cyz200303@163.com`
  - 官方网站（海外 / GitHub）：`https://github.com/Iwillbecomestrong/DeciKeeper`
  - 官方网站（国内 / Gitee）：`https://gitee.com/my_room_welcome_you/decikeeper`
  - 适用：项目主页、源码查看、涉及个人隐私信息、不便公开讨论的问题、商务与通用咨询。
- **应用内辅助工具定位**：
  - 入口：「设置 → 🧾 日志诊断 → 发送日志」
  - 定位：本地日志提取与取证工具，生成脱敏/精简后的 `app_log.txt`，供用户附加在 Issue 或邮件中。
  - 代码修正：`LogExporter.DEFAULT_RECIPIENT` 由 `support@example.com` 修正为 `cyz200303@163.com`。

### 3.2 问题反馈 4 要素范式 (Standard Reporting Paradigm)
所有渠道（Issue / 邮件 / 社区）统一遵守以下提报格式：
1. **📱 运行环境**：手机型号（如 Xiaomi 14 / Vivo X100）+ 系统版本（如 HyperOS 1.0 / OriginOS 4）+ Android 系统版本。
2. **🏷️ 应用版本**：决定守护版本号或 Git Commit SHA（例如：v1.0.0-open-source 或具体 commit）。
3. **📝 现象与复现**：
   - 发生时间（大致时刻，便于对照日志）；
   - 操作步骤（做了什么）；
   - 预期表现与实际异常表现（例如：临时放行到期未弹锁屏、某输入法被误锁、防卸载弹窗未拦截等）。
4. **📎 取证日志**：
   - 打开应用内「设置 → 🧾 日志诊断」；
   - 点击「发送日志」，选择对应的时间范围（例如：最近 5 分钟、最近 1 小时或自定义时段）；
   - 将生成的 `app_log.txt` 作为附件随邮件发送，或上传至 GitHub/Gitee Issue（注意隐去包含敏感包名等隐私）。

### 3.3 受影响文件与变更范围
1. `app/src/main/res/raw/contact_and_complaint.txt`：
   - 更新联系邮箱为 `cyz200303@163.com`，官网为 `暂无`；
   - 增设反馈渠道说明与 4 要素范式说明；
   - 投诉邮箱同步更新为 `cyz200303@163.com`。
2. `app/src/main/java/com/decikeeper/app/data/LogExporter.kt`：
   - `DEFAULT_RECIPIENT` 改为 `"cyz200303@163.com"`。
3. `app/src/main/java/com/decikeeper/app/ui/HelpActivity.kt`：
   - 第六节「日志与反馈」文案同步更新，加入 Issues 地址与 4 要素范式。
4. `docs/使用手册.md`：
   - 补充完善日志取证与 Issue 反馈规范章节。
5. (可选同步) `app/src/main/res/raw/privacy_policy.txt` 等其他法务文档联系邮箱更新。

## 4. 约束与防回归 (Constraints)
- 必须严格保护 `LegalEntryRegistryTest` 契约，不得改变法务文档的 raw 资源 ID 与版本占位符（`[待法务填写 vX.Y.Z]`）。
- 符合 `AGENT_CORE.md` 的 Local README Gate，进入 `app/src/main/res/raw/` 或 `app/src/main/java/com/decikeeper/app/data/` 前遵循对应规则。
- 绝不引入联网权限或第三方上传依赖。

## 5. 验收测试 (Acceptance Criteria)
1. **代码契约测试**：编写或更新针对 `LogExporter.DEFAULT_RECIPIENT` 的单元测试，确保其值为 `"cyz200303@163.com"`。
2. **合规文档断言测试**：验证 `contact_and_complaint.txt` 中包含 `cyz200303@163.com`、GitHub 与 Gitee 官网链接与 Issues 链接，且不存在未填的常规联系占位符。
3. **既有契约保护**：`LegalEntryRegistryTest` 7 项法务入口测试 100% 保持绿色通过。
4. **Python 测试**：`python -m unittest discover -s scripts/tests` 保持全部通过。
