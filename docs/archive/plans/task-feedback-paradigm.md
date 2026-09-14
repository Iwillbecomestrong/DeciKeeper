# 实施计划：联系信息完善与问题反馈范式规范 (task-feedback-paradigm)

## 1. 目标与依据 (Goal & Authority)
- **需求依据**：用户已确认联系邮箱为 `cyz200303@163.com`，官方网站为 GitHub (`https://github.com/Iwillbecomestrong/DeciKeeper`) 与 Gitee (`https://gitee.com/my_room_welcome_you/decikeeper`)，并在 SPEC [docs/work/task-feedback-paradigm.md](docs/work/task-feedback-paradigm.md) 中确认推行「4 要素问题反馈范式」与「三级渠道体系」。
- **实现目标**：
  1. 修复代码 [LogExporter.kt](../../../app/src/main/java/com/decikeeper/app/data/LogExporter.kt) 中的假邮箱占位符，统一为真实反馈邮箱 `cyz200303@163.com`。
  2. 在 [contact_and_complaint.txt](../../../app/src/main/res/raw/contact_and_complaint.txt) 与合规文档中规范常规联系、官方网站、投诉渠道、以及推荐的 GitHub/Gitee Issues 公开渠道。
  3. 建立 4 要素反馈范式（运行环境、应用版本、复现步骤与现象、取证日志），并在 [HelpActivity.kt](../../../app/src/main/java/com/decikeeper/app/ui/HelpActivity.kt) 与 [docs/使用手册.md](../../使用手册.md) 中同步。
  4. 保持 `LegalEntryRegistryTest` 和既有单测 100% 绿色通过，确保法务契约不被破坏。

---

## 2. 详细任务拆分 (Vertical Tasks)

### 任务 1：代码与单元测试更新 (Code & Tests)
- **目标文件**：
  - `app/src/main/java/com/decikeeper/app/data/LogExporter.kt`
  - `app/src/test/java/com/decikeeper/app/FeedbackContactContractTest.kt` (新建)
- **执行步骤**：
  1. **RED 阶段**：编写 `FeedbackContactContractTest.kt`，断言：
     - `LogExporter.DEFAULT_RECIPIENT == "cyz200303@163.com"`
     - 读取 `contact_and_complaint.txt` 资源流，断言包含 `cyz200303@163.com`、GitHub 与 Gitee 官网链接与 Issues 链接，且常规联系中不含 `[待法务填写]`。
     - 运行测试观察预期失败。
  2. **GREEN 阶段**：
     - 修改 `LogExporter.kt` 中的 `DEFAULT_RECIPIENT = "cyz200303@163.com"`。
  3. **REFACTOR 阶段**：清理冗余注释，确保代码整洁。

### 任务 2：合规文档文本更新 (Compliance Raw Docs)
- **目标文件**：
  - `app/src/main/res/raw/contact_and_complaint.txt`
  - `app/src/main/res/raw/privacy_policy.txt`
- **执行步骤**：
  1. 更新 `contact_and_complaint.txt`：
     - 常规联系邮箱：`cyz200303@163.com`
     - 官方网站（海外 / GitHub）：`https://github.com/Iwillbecomestrong/DeciKeeper`
     - 官方网站（国内 / Gitee）：`https://gitee.com/my_room_welcome_you/decikeeper`
     - 推荐渠道：新增 GitHub Issues (`https://github.com/Iwillbecomestrong/DeciKeeper/issues`) 与 Gitee Issues (`https://gitee.com/my_room_welcome_you/decikeeper/issues`)。
     - 应用内取证与反馈范式：详述「设置 → 🧾 日志诊断 → 发送日志」导出取证日志的方法，附上 4 要素反馈范式（环境、版本、复现与现象、日志附件）。
     - 投诉与举报邮箱、未成年人邮箱统一更新为 `cyz200303@163.com`。
  2. 更新 `privacy_policy.txt`：
     - 第一条与结尾联系邮箱更新为 `cyz200303@163.com`，版本占位符保持不变。
  3. 运行 `FeedbackContactContractTest` 和 `LegalEntryRegistryTest` 验证通过。

### 任务 3：应用内用户手册与外部文档同步 (User Manuals & Docs)
- **目标文件**：
  - `app/src/main/java/com/decikeeper/app/ui/HelpActivity.kt`
  - `docs/使用手册.md`
- **执行步骤**：
  1. 更新 `HelpActivity.kt` 第六节「日志与反馈」文案：
     - 说明优先在 GitHub / Gitee 提 Issue，或通过邮件发送日志。
     - 载明 4 要素反馈范式。
     - 确认点击「发送日志」的收件地址为 `cyz200303@163.com`。
  2. 更新 `docs/使用手册.md`：
     - 在「常见问题与反馈」中增加反馈范式与 Issues / 邮件渠道指引。

### 任务 4：全局回归验证与知识回写 (Verification & Convergence)
- **执行步骤**：
  1. 运行相关 Gradle 单元测试：`./gradlew :app:testDebugUnitTest --tests com.decikeeper.app.FeedbackContactContractTest` 以及 `LegalEntryRegistryTest`。
  2. 运行 Python 治理单测：`python -m unittest discover -s scripts/tests`。
  3. 检查代码树纯净度，更新 `PLAN.md` 任务状态。
  4. 准备 Review Handoff 并进行 Web GPT 审查。

---

## 3. 风险与防回归 (Risks & Safeguards)
1. **法务契约破坏风险**：
   - 必须确保 `contact_and_complaint.txt` 和 `privacy_policy.txt` 的资源文件名和在 `LegalDocumentRepository` 中的引用不变。
   - 版本号占位符 `[待法务填写 vX.Y.Z]` 严禁误改，受 `LegalEntryRegistryTest.pendingLegalReviewPlaceholderIsKeywordGreppableForLegal` 保护。
2. **Android 分享兼容性**：
   - `LogExporter.shareFile` 中只传 `Intent.EXTRA_STREAM` 避免文字覆盖附件的防御逻辑受 HISTORY 保护，严禁修改此逻辑。
