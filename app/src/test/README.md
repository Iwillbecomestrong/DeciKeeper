# DeciKeeper 单元测试套件规范

本项目测试代码位于 `app/src/test/java/com/decikeeper/app/`。为确保大型测试套件的可维护性与模块化演进，测试按领域严格分包，**禁止在根目录放置任何测试文件**。

## 测试包职责划分

| 包名 | 职责与测试对象 | 典型测试示例 |
| :--- | :--- | :--- |
| `manager` | 业务规则、状态机、单例协调器（规则判定、惩罚逻辑、临时通行、锁屏状态） | `TimeRuleManagerTest`, `PenaltyCalculationTest`, `PackageForegroundEventExtractorTest` |
| `ui` | Activity、Fragment、View、悬浮窗、布局结构与 Robolectric UI 测试 | `OverlaySafeInsetsTest`, `ClozePagerAdapterTest`, `AlarmScanCutoutContractTest` |
| `protection` | 无障碍、防卸载拦截、设备管理器、悬浮窗守护与防绕过 | `AccessibilityProtectionTest`, `DeviceAdminProtectionTest`, `UninstallBlockerTest` |
| `data` | 数据持久化、数据库操作、自动备份、序列化与数据迁移 | `AutoBackupManagerTest`, `DataMigrationTest`, `LogSanitizerTest` |
| `service` | 前台服务（FGS）、系统通知、Intent 拦截与后台监测服务 | `MonitorServiceTest`, `FastLockServiceTest` |
| `questionbank` | 题库管理、题库导入解析、词汇与填空挑战逻辑 | `QuestionBankManagerTest`, `CustomQuestionBankImportUiContractTest` |
| `oem` | 针对特定厂商 ROM（OPPO/ColorOS, Vivo/OriginOS, Xiaomi/HyperOS 等）的深度适配测试 | `OppoAdaptationTest`, `ColorOsCompatTest` |
| `governance` | 架构防腐契约、源码文本检查、单一来源门禁与目录结构守卫 | `TestPackageLayoutContractTest`, `UiVisualMasterContractTest`, `CodeStyleContractTest` |
| `testutil` | 测试辅助类、测试状态快照管理与通用测试 Runner（非测试类） | `TestScenarioManager`, `TestScenarioRunner` |
| `statistics` | 统计分析计算、历史统计图表状态与额度流水子域 | `RestStatsRecorderDatabaseLifecycleTest`, `UsageStatsCalculatorTest` |

## 目录治理规则

1. **禁止根目录平铺**：`app/src/test/java/com/decikeeper/app/` 根目录不得存在任何 `*Test.kt` 文件。
2. **白名单校验**：所有顶层测试包必须属于上述 10 个已声明包名，未知目录将由治理门禁 `TestPackageLayoutContractTest` 直接阻断。
3. **隔离与重置**：涉及全局单例修改的测试，必须使用 `TestScenarioManager` 进行环境快照并在 `@After` 中可靠恢复。
