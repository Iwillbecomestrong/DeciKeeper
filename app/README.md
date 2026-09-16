# 决定守护 (DeciKeeper) Android 应用模块 (app)

> **定位**：本模块是「决定守护」的 Android 客户端主程序源码集，负责 UI 交互呈现、前台检测、无障碍防护、勿扰时段与应用时间窗判定。
> 本文件同时作为开发人员与 AI 助手进入本模块的 **Local README Gate**。

---

## 1. 快速构建与验证

```powershell
# 编译 Kotlin 源码
.\gradlew.bat :app:compileDebugKotlin

# 运行全量 JVM 单元测试
.\gradlew.bat :app:testDebugUnitTest

# 打包 Debug APK
.\gradlew.bat :app:assembleDebug

# 真机覆盖安装：不要为调试随意 uninstall，以免清空真实配置
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. 代码包结构与职责划分

源码根路径位于 `app/src/main/java/com/decikeeper/app/`。新代码应继续朝清晰的单向职责收敛：

```text
com.decikeeper.app
├── manager/     # 业务规则管理器（CreditManager, TimeRuleManager, AppWindowManager 等）
│   ├── questionbank/ # 自定义题库管理器与解析器（CustomQuestionBankManager, CustomQuestionBankParser）
│   └── statistics/ # 统计数据分桶与仓储层（StatisticsRepository, StatsDateRangeResolver）
├── ui/          # Activity / Fragment / View / Adapter，以及部分待下沉的历史 UI 逻辑
│   ├── questionbank/ # 题库管理、智能导入与错题回顾（CustomQuestionBankActivity 等）
│   └── statistics/ # 统计图表渲染与筛选弹窗（StatisticsChartRenderer, StatisticsFilterBottomSheet）
├── protection/  # 系统级防护（无障碍拦截、防卸载、防强行停止、权限守护）
├── service/     # 核心服务（CoreService 无障碍通道、MonitorService 前台监控）
├── receiver/    # 广播接收器（BootReceiver 开机自启、AdminReceiver 设备管理器冗余入口）
├── data/        # 持久化存储与数据模型
│   ├── questionbank/ # 自定义题库与题目模型（CustomQuestionModels）
│   └── statistics/ # 统计数据模型（StatisticsModels）
└── util/        # 通用工具
```

### 核心分层原则

1. **UI → Manager / UseCase → Data**
   - 新业务判定不要继续堆进 Fragment / Activity。
   - UI 负责展示状态、收集输入和发出意图。
   - 额度、pending、规则强弱等判定优先由可测试的业务层提供。
2. **Protection 独立性**
   - 系统入口保护专注无障碍事件、设置页、卸载 / 强停 / 权限等系统交互，不与复杂额度计算混杂。
3. **Diag 取证隔离**
   - 日志与诊断只提供证据，不能反向成为放行 / 拦截的业务真相源。

现有代码仍有历史分层遗留；不要为了让文档“看起来理想”而误报当前已经完全完成架构迁移。

---

## 3. 核心对象速查

| 业务领域 | 核心文件 / 管理器 | 当前职责 |
|:---|:---|:---|
| **勿扰时段** | `manager/TimeRuleManager.kt` | 时段增删改查、跨午夜处理、业务日与 pending / 生效相关逻辑 |
| **额度与出口** | `manager/CreditManager.kt`、`ui/LockWindowManager.kt` | 额度、结余 / 借用，以及休息 / 临时解锁等锁屏交互与执行链路 |
| **应用时间窗** | `manager/AppWindowManager.kt` | 被管理应用的允许时间窗与延期生效 |
| **时间规则生效策略** | `manager/EffectiveActivationPolicyManager.kt` | 只管理勿扰时段 + 应用时间窗的「宽严有别 / 全部次日」策略 |
| **前台监控** | `service/MonitorService.kt`、`service/ForegroundSessionTracker.kt` | 前台应用识别、Session 跟踪与规则边界重判 |
| **防卸载 / 系统保护** | `protection/`、`service/CoreService.kt` | 无障碍事件捕获、设置页退栈、卸载 / 强停 / 特殊系统入口保护 |
| **使用统计** | `ui/StatisticsFragment.kt`<br>`manager/statistics/StatisticsRepository.kt`<br>`manager/statistics/StatsDateRangeResolver.kt` | 休息、起床、应用、规则保护行为统计，双图表阈值自适应引擎与两层筛选体系 |
| **自定义题库** | `manager/questionbank/CustomQuestionBankManager.kt`<br>`manager/questionbank/CustomQuestionBankParser.kt`<br>`ui/questionbank/CustomQuestionBankActivity.kt` | Markdown 智能语法解析与行号告警、AI 出题提示词、多题库管理与错题常驻回顾 |

---

## 4. 开发与测试约定

1. 新增或修改业务行为时优先建立纯 Kotlin / JVM seam，并按 RED → GREEN 验证。
2. 涉及 UI 信息架构时，不能只改布局文案；需要同时检查控制器、帮助文本和合同测试。
3. 涉及无障碍、前台识别、锁屏、闹钟、OEM 系统页的行为，自动测试通过后仍应保留真机验证。
4. 资源命名沿用现有约定：
   - 布局：`activity_*` / `fragment_*` / `dialog_*` / `item_*`
   - 控件：`tv_*` / `btn_*` / `et_*` / `switch_*` / `card_*`

5. Release 证据由 `scripts/verify_release_artifact.ps1` 生成；它检查包名、版本、签名状态、最终权限和 SHA-256，不自动推送或发布。

### 时间规则页面

底部导航统一称为 **「时间规则」**，当前只包含：

- **勿扰时段**
- **应用时间窗**
- **时间规则生效策略**

其中「宽严有别 / 全部次日」涵盖勿扰时段、全局黑名单（归属于勿扰时段）、应用时间窗与规则参数，不扩展到底层系统安全保护功能。

首页只负责展示当前状态、额度与必要入口；勿扰时段和应用时间窗的编辑统一从「时间规则」进入。

详细产品契约见：

- [`../SPEC.md`](../SPEC.md)
- [`../docs/specs/focus.md`](../docs/specs/focus.md)
- [`../docs/specs/protection.md`](../docs/specs/protection.md)
