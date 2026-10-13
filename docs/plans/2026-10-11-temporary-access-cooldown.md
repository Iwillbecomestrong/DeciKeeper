# 冷却中禁止临时解锁

用户于 2026-10-11 确认；基于 GitHub dev `30f6b02`，任务分支 `codex/block-temp-unlock-cooldown`。规模：Small。

## 已确认的边界

- 所有已生效冷却中禁止临时解锁，包括休息、临时解锁、特权结束及空档转入勿扰的冷却；休息中与空档期不豁免。
- 禁止冷却中 10 秒二级应急；冷却结束后保留原有免费与计费规则。用户后续确认彻底移除二级应急的旧入口与实现。
- 休息申请的冷却双倍成本、每周特权规则、申请超出休息或空档的计费保持原样。
- 预设在休息结束后生效的冷却，不能提前作为生效冷却；独立临时解锁冷却仍须检查。
- 不新增参数，不清理用户数据；用户后续明确授权合并到 dev 并安装。

## 实现与验收

- `LockWindowManager`：读取全局冷却及内存/持久化临时解锁截止时间；申请入口、授权与借用确认共用门禁，拒绝时不扣额度。
- 移除二级应急按钮/弹窗、专属状态与持久化读写、冷却预算/60 秒间隔策略、10 秒回锁定时器和专属日志；普通临时解锁到期链保留，旧版残留应急字段不参与任何业务判定。
- `LockScreenPresentationPolicy`：临时解锁按钮冷却中禁用并显示剩余秒数，休息与特权入口不改。
- 更新 `SPEC.md`、`docs/specs/focus.md`、`docs/user/guide.md` 与受影响测试场景。
- 单测覆盖所有申请时长、10 秒应急、精确截止边界、休息中预设冷却、休息中持久化独立冷却、旧确认请求拒绝且不扣额度。
- 必须完成全量 JVM 测试及 Release 签名构建；真机及四季视觉复测单独记录。

## 首次修改验证状态（后续删除二级应急后需重新验证）

- 修改前新增的 2 个门禁/按钮测试均失败，确认旧版可在冷却中申请。
- 修改后新增 6 项 JVM/Robolectric 测试通过；全量 1768 项：1756 通过、11 跳过、1 失败。
- 唯一失败是 `DailySegmentQuotaLedgerTest.enablingASegmentInProgressProratesByTheRemainingTimeAndCarries`（第 140 行）：预期约 0.655 分钟，实际 1.0 分钟。在隔离的原始 dev `30f6b02` 上独立复现同一失败，未修改相关生产代码或测试。基线证据保存在 `app/build/reports/cooldown-dev-baseline/`，隔离工作树已归档。
- `:app:assembleRelease`、Release lint 与签名包检查通过，APK：`app/build/outputs/apk/release/app-release.apk`，SHA-256：`aa31237ac3be3e206d603614eaa20748b77357b916b4d83472b861f334f3ee71`。联合验证命令因上述既有测试失败返回非零，不代表 Release 构建失败。
- 收尾重新刷新 GitHub dev，仍为 `30f6b02`；任务分支未落后。未做真机安装及四季视觉复测，未提交或推送。
- 本会话未提供 AOCI MCP 工具，仓库未配置 `.mcp.json`，未改写正式认知索引；AOCI 对齐待具备工具的会话完成。

## 二级应急移除验证

- 已删除专属实现及对应旧测试，新增源码/布局删除契约；持久化冷却测试增加旧版应急字段，不得导致放行。
- 首轮联合验证遇到批量测试类加载失败；单独重跑全量测试后恢复为 1768 项：1756 通过、11 跳过、1 项既有 dev 失败。删除契约、6 项冷却测试、15 项普通生命周期日志测试全部通过。
- 删除后的 Release 构建、lint 及签名包检查通过。最终 APK：`app/build/outputs/apk/release/app-release.apk`；SHA-256：`4c9d506f6639dbf2302e28fae739777313911f75873be3b1d573faf18ccee9ce`。首次 APK 的 SHA-256 不作为本次最终产物证据。
- 验证日志：`cooldown-removal-verification.log`、`cooldown-removal-tests-rerun.log`；未安装到真机，未提交或推送，AOCI 工具仍不可用，索引未同步。

## 合并与安装交付

- 实现提交 `73a5206`，合并提交 `d9f75ed`；合并前 GitHub/Gitee dev 均刷新并确认是 `30f6b02`，dev 工作区无未提交修改。合并后源码与已验证任务分支一致。
- 在 dev 工作区重新完成 Release 构建及签名检查；日志 `cooldown-merged-release.log`。APK SHA-256：`2c23a129aba83ef8c9d410d23d96d34a70e97a8e91fd805bc7b7f836b0d8f1b4`。
- 已保留数据覆盖安装到 vivo V2425A（设备 `10CF290X6R001BU`），安装返回 `Success`；包名 `com.decikeeper.app`，版本 `0.3.0` / versionCode `2`，`lastUpdateTime=2026-10-11 22:49:54`。
- 未执行真机冷却操作与四季视觉复测；全量测试仍以 1756 通过、11 跳过、1 项已复现的既有 dev 失败为准。AOCI 工具不可用，索引未同步。
