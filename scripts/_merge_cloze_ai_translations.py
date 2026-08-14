#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cloze_pool.json 合并 AI 重译：
1. option_meanings_zh <- 词表映射（有道释义替换为 AI 释义）
2. passage.zh <- 段落批次译文（Google 译文替换为 AI 译文）
"""
import json, os, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app", "src", "main", "assets")
B = os.path.join(ROOT, "scripts", ".ai_translation", "batches")

mapping = {}
for p in glob.glob(os.path.join(B, "trans_*.json")):
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    mapping.update({k.strip().lower(): v for k, v in d.items()})

passages = {}
for p in glob.glob(os.path.join(B, "cloze_passages_trans_*.json")):
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    passages.update({int(k): v for k, v in d.items()})

path = os.path.join(A, "cloze_pool.json")
with open(path, encoding="utf-8") as f:
    data = json.load(f)

opt_hit = opt_miss = 0
pass_hit = pass_miss = 0
for i, it in enumerate(data.get("items", [])):
    options = it.get("options") or {}
    meanings = it.setdefault("option_meanings_zh", {})
    for k, word in options.items():
        if not isinstance(word, str) or not word.strip():
            continue
        g = mapping.get(word.strip().lower())
        if g:
            meanings[k] = g
            opt_hit += 1
        else:
            opt_miss += 1
    zh = passages.get(i)
    if zh:
        it.setdefault("passage", {})["zh"] = zh
        pass_hit += 1
    else:
        pass_miss += 1

with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print("option_meanings: hit=%d miss=%d" % (opt_hit, opt_miss))
print("passage.zh: hit=%d miss=%d" % (pass_hit, pass_miss))
