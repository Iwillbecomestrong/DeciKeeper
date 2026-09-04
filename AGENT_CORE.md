# AI 协作协议核心 (AGENT_CORE)

> **定位**：本文件是所有 AI 编码助手（Claude / Gemini / Codex / Cursor 等）在 IronWill 项目中必须严格遵守的**唯一公共操作协议**。
> 各平台薄入口文件（`AGENTS.md`、`CLAUDE.md`、`GEMINI.md`）仅保留环境特例并指向本协议。

---

## 1. 核心运行协议：Local README Gate

在修改、创建、移动、重命名或删除任何文件前，必须执行 **Local README Gate**：
1. 定位目标路径所在的目录；
2. **首先读取离目标文件最近的 `README.md`**（如进入 `app/` 先读 `app/README.md`，进入 `scripts/` 先读 `scripts/README.md`）；
3. 严格遵守该目录的就地契约、职责边界与依赖规则；
4. 若最近目录无独立 README，遵循上级 README 及本协议全局规范。

---

## 2. 项目治理与单一真相体系 (Living Documents)

所有 AI 不得在各平台专属规则文件中复制业务需求或实现方案，必须维护项目根目录的统一真相：

| 文件 | 承担职责 | 权威内容 | 禁止写入 |
|:---|:---|:---|:---|
| [README.md](file:///g:/IronWIl0329/IronWill-OpenSource/README.md) | 项目地图与门面 | 简介、Logo、目录职责索引、快速构建、许可证 | 详细实现代码、冗长历史调试记录 |
| [SPEC.md](file:///g:/IronWIl0329/IronWill-OpenSource/SPEC.md) | **意图真相** | 已确认业务规范、两类应用划分、白名单、验收标准 | 未验证假设、调试过程流水账 |
| [PLAN.md](file:///g:/IronWIl0329/IronWill-OpenSource/PLAN.md) | **实现真相** | 总体路线、阶段里程碑、依赖关系、当前分支任务 | 单次代码补丁细节 |
| [HISTORY.md](file:///g:/IronWIl0329/IronWill-OpenSource/HISTORY.md) | **决策真相** | 架构决策 `[DECISION]`、踩坑与防回归红线、复用经验 | 普通无价值改动日志 |
| [AGENT_CORE.md](file:///g:/IronWIl0329/IronWill-OpenSource/AGENT_CORE.md) | **操作真相** | AI 行为规范、边界门禁、验证与收敛要求 | 具体业务逻辑细节 |

---

## 3. 业务红线（违反即时回滚）

1. **加严立即生效，放松次日生效**：
   - 任何涉及放宽限制（如增加额度、移出黑名单、删除勿扰时段）的逻辑，必须进入 `ConfigPendingManager` / `iron_will_rules_pending`，延迟至次日刷新时刻（默认 08:00，以全局参数配置为准）比对生效。严禁绕过！
2. **严格单向分层依赖**：
   - `UI 层 (ui/)` ➔ `业务层 (manager/)` ➔ `系统/数据层 (data/)`。
   - UI 严禁直接读写业务 `SharedPreferences`，严禁编写业务判定逻辑。
   - 业务层严禁反向 import UI 类（Activity/Fragment/View/Adapter）。
3. **取证契约神圣不可侵犯**：
   - 诊断与日志模块（`diag/`）仅用于取证与压缩归档，严禁任何业务判定或放行逻辑读取诊断日志。
4. **防御性代码保护**：
   - [HISTORY.md](file:///g:/IronWIl0329/IronWill-OpenSource/HISTORY.md) 中标注的防回归代码（如代际序号、35s 桌面计时器、5秒热窗口），在未完全理解根因前禁止删除或重写。
5. **部署安全红线**：
   - 真机安装验证一律使用 `adb install -r -d`，**严禁执行 `adb uninstall`**（避免抹掉用户本地持久化规则与词库数据）。
6. **底层技术依赖事实约束**：
   - 应用前台检测、时间拦截、防卸载三大核心功能**全部单点依赖 Android AccessibilityService（无障碍服务）**。
   - 防卸载是通过无障碍事件捕获和窗口特征拦截实现的，**严禁再使用已被废弃的 Device Admin（设备管理器）来实现防卸载**。

---

## 4. Git 规范与变更收敛 (Convergence)

### 4.1 Commit 格式
```text
type(scope): 中文简述（≤ 50 字符，祈使句，结尾无句号）
```
- `type` 范围：`feat` / `fix` / `perf` / `docs` / `test` / `refactor` / `chore` / `style`。
- 一个 commit 只做一件独立完整的事。

### 4.2 任务完成收敛门禁 (Convergence Gate)
在宣告任何开发、重构或整理任务完成前，必须执行：
1. **构建与测试验证**：运行 `python -m unittest discover -s scripts/tests` 以及相关 Gradle 构建命令。
2. **知识回写 (Knowledge Sync)**：将确认的需求变动回写 `SPEC.md`，架构进展回写 `PLAN.md`，重要踩坑回写 `HISTORY.md`，文件变动回写对应 `README.md`。
3. **代码树纯净度检查**：确保没有临时文本、日志转储或编译产物遗留在根目录或源码集中。
