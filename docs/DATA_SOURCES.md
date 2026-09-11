# 数据资产来源与版权评估 (DATA_SOURCES)

> 本文档是开源发布前的数据资产审计报告。
> **2026-08-14 处理完毕：所有第三方抓取文本（有道释义/Google 译文/商业解析）已剥离或替换为 AI 新生成文本，仓库数据层可发布。**

## 一、当前数据文件状态（已处理）

| 文件 | 内容 | 处理方式 | 当前风险 |
|------|------|----------|----------|
| cloze_pool.json | 545 道考研完形：真题原文 + 选项 + 答案 + 释义 + 译文 | 商业解析（review_explanation 等 4 字段）已剥离；选项释义 2180 条与段落译文 545 段已替换为 AI 生成 | 低：真题原文公开、释义/译文自产 |
| vocabulary_kaoyan.json | 考研词汇 + 释义 | 释义 5687 条已替换为 AI 生成 | 低：词表+自产释义 |
| vocab_merged.json / wordlist.json / passage_words.json | 词库、释义 | 释义 8713 条已替换为 AI 生成 | 低 |
| duilian_single.json | 对联 | 传统文学作品（公有领域） | 极低 |
| 考研词汇.txt | 词汇原始文本（约 43KB） | 纯词表（事实性列表） | 极低 |

> 音标为客观符号，保留原样。
> 真题原文为教育部公开历年试题；AI 释义/译文为本项目自行生成。

## 二、风险分析

1. **真题原文**：考研英语真题由教育部考试中心每年公开，题目原文本身引用/传播风险较低；但商业出版物的答案精解/长篇解析禁止收录。
2. **机器翻译与外部释义**：历史外部 API 抓取的解析已全部剥离，词汇简短释义均已由自产或 AI 清洗生成取代。
3. **技术代码与第三方库**：技术代码（Kotlin/构建脚本）为本项目原创；第三方库均采用主流开源许可（Apache-2.0 / MIT / EPL-1.0，见 `THIRD_PARTY_NOTICES.md`）。

## 三、处理方案（已执行）

**方案 D（已执行）**：cloze_pool.json 剥离商业解析内容，保留题目/选项/答案/翻译/释义：

- 已移除字段（每条 ×4）：review_explanation（商业解析书逐字内容）、explanation_source_file、answer_status、notes。
- 保留字段：真题题面（context_three_sentences / raw_passage / passage zh+en）、options、answer_label/answer_text、option_meanings_zh（简短释义）、passage 翻译。
- App 兼容性：AlarmChallengeManager 对 review_explanation 缺失有 optString 空串兜底；AlarmChallengeManagerClozePoolTest 通过。
- 效果：用户 clone 仓库直接构建即可使用（无需导入数据）；详解解析功能在开源版不提供。
- 词汇/翻译数据（vocabulary_kaoyan / vocab_merged / wordlist / passage_words / 考研词汇.txt）保留简短自产释义/翻译。

> 任何 PR 不得新增来自商业出版物或未经授权 API 抓取的解析/数据。

