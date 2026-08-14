#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开源发布用：剥离 cloze_pool.json 中的商业版权内容。

剥离字段（不可开源内容）：
  - review_explanation      商业真题解析书逐字内容（【答案精解】【考点】【解析】）
  - explanation_source_file 来源证据（商业解析书 PDF 文件名）
  - answer_status           来源证据
  - notes                   附注（空或来源相关）

保留字段（题目、选项、答案、翻译/释义）：其余全部原样保留。
原始完整数据备份到 scripts/.bak/cloze_pool.full.<ts>.json（已 gitignore，仅限个人使用）。
"""
import json, os, shutil, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "assets", "cloze_pool.json")
BAK_DIR = os.path.join(ROOT, "scripts", ".bak")
STRIP_KEYS = ["review_explanation", "explanation_source_file", "answer_status", "notes"]

with open(SRC, "r", encoding="utf-8") as f:
    data = json.load(f)

# 顶层结构: {"meta": {...}, "items": [...]}
items = data.get("items")
if not isinstance(items, list):
    print("ERROR: unexpected top-level structure, keys=" + str(list(data.keys())))
    sys.exit(1)

os.makedirs(BAK_DIR, exist_ok=True)
bak = os.path.join(BAK_DIR, "cloze_pool.full.%s.json" % time.strftime("%Y%m%d-%H%M%S"))
shutil.copy2(SRC, bak)

stripped = 0
for entry in items:
    for k in STRIP_KEYS:
        if k in entry:
            del entry[k]
            stripped += 1

# 同步 meta：移除对已剥离字段的引用
meta = data.get("meta")
if isinstance(meta, dict):
    meta["display_rule"] = (
        "闹钟答题页显示 context_three_sentences、options、option_meanings_zh；"
        "详解解析已从开源版剥离（见 docs/DATA_SOURCES.md）"
    )
    meta["opensource_note"] = (
        "review_explanation/explanation_source_file/answer_status/notes 已剥离；"
        "完整版数据请自备，勿再分发商业解析内容"
    )

with open(SRC, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print("entries=%d stripped_fields=%d" % (len(items), stripped))
print("backup=%s" % bak)
