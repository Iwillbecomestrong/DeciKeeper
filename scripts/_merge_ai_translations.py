#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 AI 重译映射合并回写词库数据文件（替换有道抓取释义）。"""
import json, os, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app", "src", "main", "assets")
B = os.path.join(ROOT, "scripts", ".ai_translation", "batches")

# 1) 汇总映射（键为小写词形）
mapping = {}
for p in glob.glob(os.path.join(B, "trans_*.json")):
    with open(p, encoding="utf-8") as f:
        d = json.load(f)
    for w, t in d.items():
        mapping[w.strip().lower()] = t
print("mapping entries: %d" % len(mapping))

def first_sense(trans):
    seg = trans.split("；")[0].split("，")[0].strip()
    return seg or trans

def apply_array(fname, word_key, trans_key, extra_short_key=None):
    path = os.path.join(A, fname)
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    hit = miss = 0
    for it in data:
        w = (it.get(word_key) or "").strip().lower()
        t = mapping.get(w)
        if t:
            it[trans_key] = t
            if extra_short_key:
                it[extra_short_key] = first_sense(t)
            hit += 1
        else:
            miss += 1
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("%s: hit=%d miss=%d" % (fname, hit, miss))

apply_array("vocabulary_kaoyan.json", "word", "translation")
apply_array("vocab_merged.json", "text", "translation")
apply_array("wordlist.json", "text", "translation", "shortTranslation")

# 2) passage_words.json（值直接替换为 AI 释义）
path = os.path.join(A, "passage_words.json")
with open(path, encoding="utf-8") as f:
    pw = json.load(f)
hit = miss = 0
for w in list(pw.keys()):
    t = mapping.get(w.strip().lower())
    if t:
        pw[w] = t
        hit += 1
    else:
        miss += 1
with open(path, "w", encoding="utf-8") as f:
    json.dump(pw, f, ensure_ascii=False, indent=1)
print("passage_words.json: hit=%d miss=%d" % (hit, miss))
