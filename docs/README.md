# 知止 (ZhiZhi) 文档中心 (docs)

> **定位**：本目录收录「知止」项目的所有架构设计、业务规格、使用指南、开源合规及历史工程档案。
> 本文件同时作为进入 `docs/` 的 **Local README Gate**。

---

## 1. 文档架构地图

```text
docs/
├── ARCHITECTURE.md          # 核心系统架构设计、依赖流向与重构路线图
├── DATA_SOURCES.md          # 数据集来源、开源版权清洗合规与剥离说明
├── THIRD_PARTY_NOTICES.md   # 第三方开源组件与库许可证声明
├── PUBLISHING.md            # Gitee/GitHub 双推、构建签名与发版检查清单
├── 使用手册.md               # 面向最终用户的详细功能配置与使用指南
├── specs/                   # 业务规格与详细需求
│   └── requirements.md      # 全量产品需求规格（原 Requirements.md）
├── history/                 # 历史演进与踩坑排查记录
│   ├── issue_tracker.md     # 完整历史缺陷与排查记录（原 Issue_Tracker.md）
│   └── fixtures/            # 历史排查转储证据与样本
├── images/                  # 视觉与图档资源
│   ├── logo.png             # 1024x1024 高清灯塔 Logo 矢量源图
│   └── screenshots/         # 核心界面预览与真机截图
├── content/                 # B 站首发内容稿件（见 content/README.md）
│   ├── narration_count.py   # 口播字数与时长统计工具（稿件唯一真相源）
│   ├── video1_narration.md  # 视频 1 定稿旁白
│   ├── video2_outline.md    # 视频 2 节点排期与录屏分段
│   └── video3_outline.md    # 视频 3 结构草案
└── ai/                      # AI 演进与辅助文档
    └── LOG_EVENT_MIGRATION.md # 日志标签审计与规范化迁移清单
```

---

## 2. 核心文档导览

| 文档 | 读者角色 | 核心内容 |
|:---|:---|:---|
| [ARCHITECTURE.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/ARCHITECTURE.md) | 架构师 / 核心贡献者 | 描述 UI ➔ Manager ➔ Data 单向依赖架构、持久化三态（Active/Baseline/Pending）与核心重构规划。 |
| [specs/requirements.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/specs/requirements.md) | 产品 / 需求分析 | 详细记录勿扰时段、应用分类、冷却机制与额度规则的全量业务定义。 |
| [history/issue_tracker.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/history/issue_tracker.md) | 开发者 / 维护者 | 沉淀真机环境下的各类极端异常、厂商 ROM 诡异行为排查及防回归记录。 |
| [DATA_SOURCES.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/DATA_SOURCES.md) | 合规审查 / 贡献者 | 明确内置考研词汇与真题题面的来源合法性，严禁提交任何带商业版权的内容。 |
| [PUBLISHING.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/PUBLISHING.md) | 维护者 / 发布负责人 | Gitee/GitHub 双推配置、构建签名流程、分发渠道分工与发版检查清单。 |
| [使用手册.md](file:///g:/IronWIl0329/IronWill-OpenSource/docs/%E4%BD%BF%E7%94%A8%E6%89%8B%E5%86%8C.md) | 终端用户 | 简明生动的产品上手指南与最佳自律实践。 |
| [content/](file:///g:/IronWIl0329/IronWill-OpenSource/docs/content/README.md) | 内容运营 | B 站首发三支视频的口播稿、节点排期与字数统计工具。 |
