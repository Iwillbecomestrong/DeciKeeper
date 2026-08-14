# IronWill

一个不商量、不提供“就这一次”机会的 Android 自律工具。

**核心逻辑**：你事先定好规则 → 到时间/遇到情况 → IronWill 自动锁机 → 必须消耗休息额度或完成任务才能解锁。

## 功能

- **勿扰时段（DND）**：一天分多个时段，每个时段独立休息额度；时段内黑名单应用被拦截，消耗额度解锁；额度可跨时段结转、可预支（双倍惩罚）。
- **本时段独立黑名单**：每个时段可继承全局黑名单，也可脱钩独立管理（支持全选/反选、恢复为全局）；桌面/输入法/电话/短信等系统关键应用永不可被锁。
- **应用时间窗**：纯娱乐应用只能在指定时间窗内打开。
- **休息 / 临时解锁 / 每周特权**：多级解锁机制，冷却期双倍扣费。
- **闹钟挑战**：数学/诗词/单词/选词填空/扫码挑战，完成才能关掉闹钟；防关机保护。
- **防卸载 / 无障碍防护**：拦截应用信息页、强行停止、卸载确认、隐私空间添加。
- **统计面板**：周/月/年使用统计。
- **规则延迟生效**：加严立即生效、放松次日生效。

## 构建

    gradlew.bat :app:assembleDebug   # Windows
    # 产物：app/build/outputs/apk/debug/app-debug.apk

- 最低 SDK 26（Android 8.0），目标 SDK 36，Kotlin。
- 依赖见 THIRD_PARTY_NOTICES.md（均为 Apache-2.0 / MIT）。

## 真机安装

    adb install -r -d app/build/outputs/apk/debug/app-debug.apk

**禁止 adb uninstall**：卸载会抹掉全部用户数据（黑名单/额度/词库）。

## 数据资产说明

- app/src/main/assets/ 下的词库释义为 AI 生成的简洁释义；真题题面源自历年考研英语真题（教育部公开）；详解解析已从开源版剥离。
- 数据来源与版权说明见 docs/DATA_SOURCES.md。请勿向本仓库提交来自商业出版物或翻译服务抓取的内容。

## 架构与贡献

- 架构分层与模块职责：docs/ARCHITECTURE.md
- 命名规范、业务红线、PR 流程：CONTRIBUTING.md
- 欢迎 Issue 与 PR。

## 许可证

GPL-3.0（见 LICENSE）。数据文件版权状态见 docs/DATA_SOURCES.md。
