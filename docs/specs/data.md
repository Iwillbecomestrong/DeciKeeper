# 数据、业务日与备份规格

> **职责**：本文件定义决定守护当前本地数据、Active / Baseline / Pending、业务日边界、设置备份兼容与关键恢复数据的跨模块契约。
>
> 业务算法本身分别见 [`focus.md`](focus.md)、[`alarm.md`](alarm.md) 与 [`protection.md`](protection.md)。

---

## 1. 数据原则

决定守护当前核心运行数据保存在设备本地。

稳定原则：

- 规则执行不能依赖云端后端在线；
- 调试/诊断数据不能成为规则执行的唯一真相源；
- 数据迁移不能因为项目从 IronWill 更名为 DeciKeeper 就让老用户规则失效；
- 调试安装和升级应尽量使用覆盖安装，避免无必要清空用户规则、题库与历史记录。

当前 Android 工程不声明普通网络访问权限，核心功能按离线模式设计。

---

## 2. Active / Baseline / Pending

决定守护多个规则模块使用三层语义：

| 层 | 含义 |
| --- | --- |
| **Active** | 当前真正参与执行的配置 |
| **Baseline** | 用于判断修改是加严、放宽还是恢复既有状态的基准 |
| **Pending** | 已接受但尚未到合法生效时刻的变更 |

### 2.1 约束

- UI 保存成功不等于新值已经成为 Active；
- 不利/放宽变更必须与 Baseline 比较，不能只与当前 Active 做简单差异判断；
- Pending 未到期时，执行逻辑继续使用当前有效规则；
- Pending 到期后应以可恢复、幂等的方式落地；
- 策略切换、导入和重启后不得留下互相矛盾的 Active / Pending 组合。

### 2.2 适用模块

当前明确存在对应语义的模块包括：

- 勿扰时段；
- 应用时间窗；
- 防卸载；
- 隐私空间等部分保护状态；
- 其他通过 `ConfigPendingManager` 管理延期的配置。

具体例外以各模块规格为准。

---

## 3. 业务日

决定守护不能简单把自然日 `00:00 → 24:00` 当作所有规则的“今天”。

业务日用于统一：

- 勿扰时段归属；
- 额度使用与结余；
- 下一时段借用；
- pending 的下一合法生效点；
- 首页和统计对“今天”的解释。

当前实现的业务日以**用户偏好的配置刷新时点（`config_refresh_time`，默认 08:00）**为首要真理源（v3 反转优先级）；若未配置或处于特殊跨天时段，以规则时段边界作为 fallback，使跨午夜的深夜时段仍归属于它开始时所在的一侧，而不是午夜突然清空上下文。

### 3.1 跨模块红线

以下模块不得各自发明一套“今天”：

- `TimeRuleManager`；
- `CreditManager`；
- `ConfigPendingManager`；
- 首页额度展示；
- 统计聚合。

如果业务日算法改变，必须把这些模块作为一组一起审计。

---

## 4. 当前本地持久化

当前实现主要使用 SharedPreferences，并辅以 Device Protected Storage (DPS) 保存需要在 Direct Boot 阶段恢复的关键状态。

典型数据域包括：

| 数据域 | 当前用途 |
| --- | --- |
| 时间规则 Active / Baseline / Pending | 勿扰时段、全局相关规则、延期变更 |
| App Window Active / Baseline / Pending | 应用时间窗及待生效修改 |
| Ledger / Credit | 额度、结余、借用、特权使用记录 |
| Anti-uninstall | 防卸载状态与关闭 pending |
| Accessibility control | 防关权限业务状态 |
| Privacy protection | 隐私空间保护状态 |
| Alarm prefs | 闹钟条目、默认设置、active session 等 |
| Device Protected Storage (DPS) | Direct Boot 阶段可用数据：关键闹钟状态、时段规则缓存、额度账本镜像与无障碍启动凭据 |
| Change log / diagnostics | 变更记录与排障信息 |

具体 SharedPreferences 文件名属于当前实现细节；新增数据域时应优先保证语义清晰、迁移安全和可恢复，而不是依赖历史 `decikeeper_*` 命名永远不变。

---

## 5. 闹钟关键状态的持久化红线

闹钟 active / vibration 状态具有比普通 UI 偏好更高的可靠性要求。

当前稳定要求：

- 关键 active 状态在需要保证落盘顺序时使用同步提交；
- 需要 Direct Boot 恢复的状态双写到设备保护存储；
- 闹钟服务和恢复入口必须能在设备重启后读取对应状态；
- 明确完成/关闭闹钟后必须清理 active 状态，避免幽灵闹钟恢复；
- 恢复机制要幂等，不能因为多个 boot/recovery 入口同时触发就重复创建独立 session。

详细流程见 [`alarm.md`](alarm.md)。

---

## 6. 设置备份协议与自动备份

当前备份体系包含**手动备份/恢复**与**后台自动备份**两条通道：

### 6.1 备份格式与兼容标识

`SettingsBackupManager` 支持：

- **当前类型标识**：`decikeeper_settings_backup`；
- **当前 schema version**：`2`；
- **兼容历史类型标识**：`zhizhi_settings_backup`、`ironwill_settings_backup`（全面受理并自动平滑迁移）。

### 6.2 自动备份通道 (AutoBackupManager)

系统通过 `AutoBackupManager` 提供本地自动容灾双写：
- 写入公共存储（`Documents/DeciKeeper/`）与应用私有目录（`files/auto_backup/`）；
- 具备历史 `ZhiZhi` 目录的回退与自动平滑收敛能力；
- 规则变动或关键时间节点自动创建快照，防范误操作与极端异常丢失。

### 6.3 隐私与排除红线

- **隐私授权状态绝不备份**：根据 `backup_rules.xml` 与 `data_extraction_rules.xml`，用户隐私政策同意状态（`privacy_policy_accepted` 等 consent）**严格排除在备份/云恢复之外**，恢复或迁移后必须由当前设备用户重新确认授权；
- 合法旧备份必须继续能够迁移到 DeciKeeper，不能仅因品牌更名拒绝老数据；
- 导入不能把格式无法识别的数据静默当成成功；
- 导入涉及受保护规则时，必须尊重当前模块的 Active / Baseline / Pending 语义与适应期状态。

---

## 7. 数据与统计的边界

统计页面只能消费已经发生的真实事件/额度记录，不能为了展示效果反向改写业务账本。

尤其：

- 每周特权使用记录与普通休息额度记录必须保持可区分，避免特权统计污染普通配额；
- 已借额度是未来扣减义务，不是可再次消费的余额；
- 结余分母必须来自真实转入快照，不得用上一时段原始总额度冒充；
- 统计聚合必须遵守同一业务日边界。

统计 UI 的图表和布局不属于本文件职责。

---

## 8. 包名与升级兼容

当前官方 Android 包名：

`com.decikeeper.app`

升级兼容原则：

- 新版本应优先覆盖升级；
- 不随意更换包名导致系统视为全新 App；
- 旧 `decikeeper_*` prefs 名称可以在内部继续存在，只要迁移和语义清晰；
- 文档面向用户统一使用「决定守护 / DeciKeeper」，内部历史 key 不需要为了品牌统一而冒险批量重命名。

---

## 9. 非目标

本文件不负责：

- 定义具体 UI 图表；
- 定义每种规则的加严/放宽分类细节；
- 记录一次性的调试 dump；
- 记录已经失效的历史需求计划；
- 把 SharedPreferences 当成不可改变的长期架构承诺。
