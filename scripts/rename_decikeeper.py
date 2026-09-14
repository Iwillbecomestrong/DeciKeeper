#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
决定守护 (DeciKeeper) 全局更名脚本
知止 -> 决定守护
ZhiZhi -> DeciKeeper
zhizhi -> decikeeper
iron_will -> decikeeper   (prefs 键名系列，不触碰 ironwill_settings_backup 连写)

排除：二进制文件、.git 内部、历史档案 (HISTORY.md / CHANGELOG.md) 由人工处理。
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

BINARY_EXT = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".bmp",
    ".apk", ".aab", ".jar", ".aar", ".class", ".dex",
    ".zip", ".gz", ".7z", ".rar",
    ".mp4", ".mov", ".avi", ".mp3", ".wav", ".flac", ".ogg", ".m4a",
    ".ttf", ".otf", ".woff", ".woff2",
}

SKIP_FILES = {
    "HISTORY.md",        # 历史档案：人工处理
    "CHANGELOG.md",      # 历史档案：人工处理
    ".history_full.md",  # 本地临时文件
}

REPLACEMENTS = [
    ("知止", "决定守护"),
    ("ZhiZhi", "DeciKeeper"),
    ("zhizhi", "decikeeper"),
    ("iron_will", "decikeeper"),
]


def is_binary(path: str) -> bool:
    ext = os.path.splitext(path)[1].lower()
    if ext in BINARY_EXT:
        return True
    if ext == ".svg":
        return False
    # 内容探测：含 NUL 字节视为二进制
    try:
        with open(path, "rb") as f:
            chunk = f.read(8192)
        return b"\x00" in chunk
    except Exception:
        return True


def read_text(path: str):
    with open(path, "rb") as f:
        raw = f.read()
    has_bom = raw.startswith(b"\xef\xbb\xbf")
    if has_bom:
        raw = raw[3:]
    return raw.decode("utf-8", errors="replace"), has_bom


def write_text(path: str, text: str, has_bom: bool):
    data = text.encode("utf-8")
    if has_bom:
        data = b"\xef\xbb\xbf" + data
    with open(path, "wb") as f:
        f.write(data)


def main():
    # 获取 git 跟踪文件
    out = subprocess.run(
        ["git", "ls-files", "-z"], capture_output=True, check=True
    )
    files = [f for f in out.stdout.decode("utf-8").split("\0") if f]
    print(f"tracked files: {len(files)}")

    changed = 0
    total_repl = 0
    for rel in files:
        if os.path.basename(rel) in SKIP_FILES:
            print(f"  [skip] {rel}")
            continue
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            continue
        if is_binary(path):
            continue
        try:
            text, has_bom = read_text(path)
        except Exception as e:
            print(f"  [read-fail] {rel}: {e}")
            continue

        orig = text
        count = 0
        for old, new in REPLACEMENTS:
            n = text.count(old)
            if n:
                text = text.replace(old, new)
                count += n
        if count == 0:
            continue
        write_text(path, text, has_bom)
        changed += 1
        total_repl += count
        print(f"  [ok] {rel} ({count} replacements)")

    print(f"\nDONE: {changed} files changed, {total_repl} replacements")


if __name__ == "__main__":
    main()
