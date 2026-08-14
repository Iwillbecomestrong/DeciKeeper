#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把去重词表按字母范围切成批次文件，供翻译子代理逐批处理。"""
import json, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INV = os.path.join(ROOT, "scripts", ".ai_translation", "inventory.json")
OUT = os.path.join(ROOT, "scripts", ".ai_translation", "batches")
os.makedirs(OUT, exist_ok=True)

with open(INV, encoding="utf-8") as f:
    inv = json.load(f)
words = sorted(inv["words"].keys())

BATCHES = [
    ("a", lambda w: w.startswith("a")),
    ("bc", lambda w: w[0] in "bc"),
    ("def", lambda w: w[0] in "def"),
    ("ghi", lambda w: w[0] in "ghi"),
    ("jkl", lambda w: w[0] in "jkl"),
    ("mno", lambda w: w[0] in "mno"),
    ("pq", lambda w: w[0] in "pq"),
    ("rst", lambda w: w[0] in "rst"),
    ("uvwxyz", lambda w: w[0] in "uvwxyz"),
]

for name, pred in BATCHES:
    batch = [w for w in words if pred(w)]
    if not batch:
        continue
    p = os.path.join(OUT, "words_%s.json" % name)
    with open(p, "w", encoding="utf-8") as f:
        json.dump({"batch": name, "words": batch}, f, ensure_ascii=False, indent=0)
    print("%s: %d words -> %s" % (name, len(batch), os.path.basename(p)))
