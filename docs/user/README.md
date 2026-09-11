# docs/user —— 用户文档

> **Local README Gate**：进入本目录前先读本文件。

本目录是**面向最终用户**的完整说明，也是 App 内置帮助的**单一来源**。它不重新定义业务规则：每条规则的权威归属仍然是 [`../../SPEC.md`](../../SPEC.md) 与 [`../specs/`](../specs/)。

| 文件 | 回答的问题 | 读者 |
| --- | --- | --- |
| [`getting-started.md`](getting-started.md) | 第一次使用怎么开始？ | 新用户 |
| [`guide.md`](guide.md) | 每个功能具体怎么工作？口径是什么？ | 使用者 |
| [`permissions.md`](permissions.md) | 每项权限做什么？数据存在哪里？ | 关心隐私的用户 |

## 与 App 内置帮助的关系

App 的「帮助」页面**不维护第二份正文**。构建时 `app/build.gradle.kts` 的 `syncUserGuideAsset` 任务把上面三份文件按 `getting-started → guide → permissions` 的顺序同步进 assets，`HelpActivity` 依次渲染：

- 源变更的**唯一入口**是本目录下的 Markdown；
- 契约由 `HelpManualSingleSourceContractTest` 与 `TemporaryAccessUserFacingCopyContractTest` 守护；
- 因此修改用户文档后需要重新构建 APK，App 内才会看到新内容。

## 维护规则

1. 用户文档改写后，必须同步检查 `app/src/test/java/com/decikeeper/app/HelpManualSingleSourceContractTest.kt` 中断言的原文串，否则单测会失败。
2. 用户入口名与系统模块名不同（见 [`../product/system-overview.md`](../product/system-overview.md) 术语规范）：对用户写「时间规则」「挑战闹钟」，不要写「规则保护」「起床系统」。
3. 历史名称（IronWill / ZhiZhi / 知止 / 承诺保护系统）不得出现在本目录。
