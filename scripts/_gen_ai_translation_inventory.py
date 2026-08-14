#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成待 AI 重译的去重词表清单（不修改任何数据）。"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "scripts", ".ai_translation")

words = {}  # word -> {"sources": [...], "old": old_translation}

def add(word, old, src):
    if not word or not isinstance(word, str):
        return
    w = word.strip()
    if not w:
        return
    e = words.setdefault(w.lower(), {"word": w, "sources": [], "old": []})
    if src not in e["sources"]:
        e["sources"].append(src)
    if old and old not in e["old"]:
        e["old"].append(str(old)[:120])

# 1) 三个词库数组文件
for fname, key_text in [
    ("vocabulary_kaoyan.json", "word"),
    ("vocab_merged.json", "text"),
    ("wordlist.json", "text"),
]:
    with open(os.path.join(A, fname), encoding="utf-8") as f:
        data = json.load(f)
    for it in data:
        w = it.get(key_text) or it.get("word")
        old = it.get("translation") or it.get("shortTranslation") or ""
        add(w, old, fname)

# 2) passage_words.json（word -> {...} 映射）
with open(os.path.join(A, "passage_words.json"), encoding="utf-8") as f:
    pw = json.load(f)
if isinstance(pw, dict):
    for w, v in pw.items():
        old = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)[:120]
        add(w, old, "passage_words.json")

# 3) 考研词汇.txt 行文本
with open(os.path.join(A, "考研词汇.txt"), encoding="utf-8", errors="ignore") as f:
    for line in f:
        line = line.strip()
        if line:
            # 取第一个空格前的词（多数行是 "word translation" 格式）
            w = line.split()[0].strip(",.;:（）()[]")
            add(w, "", "考研词汇.txt")

os.makedirs(OUT, exist_ok=True)
out_file = os.path.join(OUT, "inventory.json")
with open(out_file, "w", encoding="utf-8") as f:
    json.dump({"total_unique": len(words), "words": words}, f, ensure_ascii=False, indent=1)

print("unique_words=%d" % len(words))
print("inventory=%s" % out_file)
