# 知止 (ZhiZhi) Android 应用模块 (app)

> **定位**：本模块是「知止」的 Android 客户端主程序源码集，负责 UI 交互呈现、前台检测、无障碍防护、勿扰时段与时间窗判定。
> 本文件同时作为开发人员与 AI 助手进入本模块的 **Local README Gate**。

---

## 1. 快速构建与验证

```powershell
# 编译 Kotlin 源码
.\gradlew.bat :app:compileDebugKotlin

# 运行全量 JVM 单元测试（纯 JVM 秒级运行，无需模拟器）
.\gradlew.bat :app:testDebugUnitTest

# 打包 Release APK（默认推荐）
.\gradlew.bat :app:assembleRelease
# 产物位置：app/build/outputs/apk/release/app-release.apk

# 真机覆盖安装（严禁 uninstall，否则清空用户本地配置与词库）
adb install -r -d app/build/outputs/apk/release/app-release.apk
```

---

## 2. 代码包结构与职责划分

源码根路径位于 `app/src/main/java/com/zhizhi/app/`，严格遵循单向依赖架构：

```text
com.zhizhi.app
├── manager/     # 业务规则管理器（CreditManager, TimeRuleManager, AppWindowManager 等）
├── ui/          # 视图展现层（Activity, Fragment, Adapter, Dialog）
├── protection/  # 系统级防护（无障碍拦截、防卸载、防强行停止、权限守护）
├── service/     # 核心服务（CoreService 无障碍通道、MonitorService 前台监控）
├── receiver/    # 广播接收器（BootReceiver 开机自启、DeviceAdmin 设备管理器）
├── data/        # 持久化存储与数据模型
└── util/        # 纯通用工具类（日期格式化、字符串处理等）
```

### 核心分层依赖铁律
1. **UI ➔ Manager ➔ Data**：
   - UI 层只负责渲染视图、收集输入和调用 Manager 方法，严禁直接读写业务 `SharedPreferences`。
   - 业务 Manager 层严禁反向 `import` UI 类（Activity / Fragment / View / Adapter）。
2. **Protection 独立性**：
   - 防护体系逻辑独立封闭，专注于系统交互层面的退栈与弹窗拦截，不与复杂业务计算混杂。
3. **Diag 取证隔离**：
   - 诊断与日志模块（`diag/`）代码严禁干预任何业务判定与放行决策。

---

## 3. 核心对象速查

| 业务领域 | 核心文件 / 管理器 | 职责描述 |
|:---|:---|:---|
| **勿扰时段 (DND)** | `manager/TimeRuleManager.kt` | 时段增删改查、跨午夜时段归一化、加严/放松差异化生效 |
| **名单与配额** | `manager/CreditManager.kt`<br>`manager/SegmentBlacklist.kt` | 黑名单生效判定、休息额度计算、借用惩罚与阶梯冷静期计算 |
| **时间窗管控** | `manager/AppWindowManager.kt` | 纯娱乐应用在勿扰间隙（Gap）及全天的时间窗限制与延迟生效 |
| **前台监控** | `service/MonitorService.kt`<br>`service/ForegroundSessionTracker.kt` | 前台应用识别、滑窗投票、未关闭前台会话跟踪、规则边界锁定 |
| **覆盖层状态机** | `manager/LockWindowManager.kt` | 锁屏 Window 管理、代际（Generation）验证、到期重锁兜底 |
| **防卸载守护** | `protection/AntiUninstallProtection.kt`<br>`service/CoreService.kt` | 无障碍事件捕获、5秒热窗口拦截、设置页退栈 |

---

## 4. 开发与测试约定

1. **Context Header**：所有新增 Kotlin 文件须包含标准 KDoc 说明，阐明其所属分层、角色与核心约束。
2. **纯逻辑 TDD**：业务判定（时间计算、加严分析、惩罚算法）优先编写可纯 JVM 运行的独立单元测试。
3. **资源命名规范**：
   - 布局：`activity_*.xml` / `fragment_*.xml` / `dialog_*.xml` / `item_*.xml`
   - 控件 ID：`tv_*` (TextView) / `btn_*` (Button) / `et_*` (EditText) / `switch_*` (Switch)

### 首页时段展示

首页展示当前及下一时段、额度结余与借用；不再提供弹性调度入口。时段编辑统一位于「专注规则」。
