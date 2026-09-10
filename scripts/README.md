# 决定守护 (DeciKeeper) 工具与脚本集合 (scripts)

> **定位**：本目录包含「决定守护」数据清洗合规管线、日志导出审计工具及自动化辅助脚本。
> 本文件同时作为进入 `scripts/` 目录的 **Local README Gate**。

---

## 1. 核心工具一览

| 脚本 | 用途 | 运行方式 |
|:---|:---|:---|
| `audit_log_export.py` | 运行日志导出取证审计与标签分类器（确保日志不泄露敏感信息且符合规范） | `python scripts/audit_log_export.py <log_file>` |
| `_clean_cloze_opensource.py` | 开源合规发布工具：剥离 `cloze_pool.json` 中的商业版权内容（解析书逐字稿与来源文件） | `python scripts/_clean_cloze_opensource.py` |
| `_gen_wordlist.py` | 内置词库处理与轻量化释义生成工具 | `python scripts/_gen_wordlist.py` |
| `archive/` | 历史补丁与单次迁移脚本归档 | 仅供历史审计参考 |

---

## 2. 自动化测试

本目录工具拥有专属单元测试集：

```powershell
# 执行脚本单元测试套件
python -m unittest discover -s scripts/tests
```

测试应保持 100% 通过（或在无内部特定设备日志时受控跳过对应样本用例），贡献脚本改动时需补充对应测试用例。
