#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取 cloze_pool.json 的待 AI 重译清单：
1. 选项词（options 值）→ 与已有词表映射比对，输出缺词清单
2. passage.en → 切成段落翻译批次文件
"""
import json, os, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app", "src", "main", "assets")
B = os.path.join(ROOT, "scripts", ".ai_translation", "batches")
OUT = os.path.join(ROOT, "scripts", ".ai_translation")

with open(os.path.join(A, "cloze_pool.json"), encoding="utf-8") as f:
    cloze = json.load(f)
items = cloze.get("items", [])

# 已有映射
mapping = {}
for p in glob.glob(os.path.join(B, "trans_*.json")):
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    mapping.update({k.strip().lower(): v for k, v in d.items()})

# 1) 选项词
opt_words = {}
for it in items:
    for k, v in (it.get("options") or {}).items():
        if isinstance(v, str) and v.strip():
            opt_words.setdefault(v.strip().lower(), v.strip())

missing_opts = {w: w for w in opt_words if w.lower() not in mapping}
print("option words: %d unique, missing from mapping: %d" % (len(opt_words), len(missing_opts)))
with open(os.path.join(OUT, "cloze_option_missing.json"), "w", encoding="utf-8") as f:
    json.dump({"words": sorted(missing_opts.keys())}, f, ensure_ascii=False, indent=0)

# 2) passage.en 批次
passages = []
for i, it in enumerate(items):
    en = (it.get("passage") or {}).get("en", "")
    if not en or not isinstance(en, str):
        continue
    passages.append({"index": i, "en": en})

BATCH_SIZE = 140
for bi in range(0, len(passages), BATCH_SIZE):
    chunk = passages[bi:bi + BATCH_SIZE]
    p = os.path.join(B, "cloze_passages_%d.json" % (bi // BATCH_SIZE))
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"batch": bi // BATCH_SIZE, "items": chunk}, f, ensure_ascii=False, indent=0)
    print("passage batch %d: %d items -> %s" % (bi // BATCH_SIZE, len(chunk), os.path.basename(p)))
print("total passages: %d" % len(passages))
