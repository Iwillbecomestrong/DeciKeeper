# 质量门禁与 Windows 测试隔离计划

状态：详细方案，未实施。日期：2026-09-16。

## 目标与已知事实

让新增高严重性问题阻止合入，使不同工作区测试互不干扰。当前 lint 配置 abortOnError=false、checkReleaseBuilds=false；baseline 是历史豁免记录，不是当前缺陷数量证明。Windows ASCII 测试路径仅含 project.name 和 variant，不同 checkout 可能碰撞，实际并发故障尚未复现。

## 范围

app/build.gradle.kts、app/lint-baseline.xml、.github/workflows/android-build.yml、Windows 手动 workflow、相关脚本及贡献指南。不开启无差别升级，不为了数量清理必要 OEM 防御，不用源码字符串断言替代行为测试。

状态：JVM/Release lint 门禁、Windows 工作区摘要隔离、Markdown 链接检查与 CI 调用已实施；双 checkout 并行和临时失败演练待补充。日期：2026-09-16。

## 实施步骤

1. 在确定的版本记录 JVM/Release/lint 基线；已有失败分别归因，不能算作新改动导致或静默跳过。
2. 检查 lint 当前报告、已有 baseline 的旧路径及失效条目。对仍有效的必要豁免记录理由与证据；禁止直接重新生成全量 baseline 吞掉新问题。
3. 逐步启用新增 error/fatal 阻断；确认 Release 检查实际覆盖范围，不能只修改配置却未在 CI 调用。
4. Windows 测试缓存路径加入规范化工作区路径的稳定摘要及任务身份，保留 ASCII；清理前验证目标在专用任务目录内，不能清理其他工作区。
5. 验证类路径重建是否遗漏 Java 测试、资源或非缓存依赖；缺失才修，不重写正常构建体系。
6. 在两个独立 checkout 并行运行受控测试，证明路径不共享、清理不互删、结果来自各自源码。遵循机器空闲资源约束。
7. 增加文档相对链接检查；忽略代码块、锚点和外部 URL 时明确规则，不把网络短暂失败作为源码错误。
8. CI 输出实际测试数量和失败报告，不在贡献文档永久硬编码“1300+ 全绿”。

## 测试与验收

- 有意引入一个临时 lint 错误证明门禁失败，再撤销该测试变更；历史批准豁免仍可正常通过。
- 两 checkout 同时测试互不污染；删除某个测试后不会运行旧 class。
- JVM 全量与 Release 构建通过，失败报告可读取，日志无敏感配置。
- 真实依赖 Android 行为的分支使用 Robolectric 或设备测试；纯规则直接用 JVM，避免只验证实现文本。
- CI 实际执行 lint 与文档检查，正常 PR 不依赖 self-hosted 私有环境。

## 交付顺序与回退

先测试目录隔离，再 lint 基线审阅，再 CI 门禁。门禁失败定位具体规则；只批准有证据的单项例外，不恢复全局忽略错误。已实测 Markdown 检查通过、`lintRelease` 通过且未扩大 baseline；当前 warning 仍需后续专项治理。OEM 真机验证是独立结果，单测通过不代表整机可靠。
