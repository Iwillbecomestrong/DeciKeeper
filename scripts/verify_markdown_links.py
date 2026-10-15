#!/usr/bin/env python3
"""Check local Markdown links without contacting external sites."""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import unquote


LINK_RE = re.compile(r"(?<!!)(?:\[[^\]]*\])\(([^)]+)\)")


def iter_markdown(root: Path):
    yield from sorted(root.rglob("*.md"))


def check_file(path: Path, root: Path) -> list[str]:
    errors: list[str] = []
    in_fence = False
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for raw_target in LINK_RE.findall(line):
            target = raw_target.strip().split(maxsplit=1)[0].strip("<>")
            if not target or target.startswith(("#", "http://", "https://", "mailto:", "data:", "file:")):
                continue
            target_path = unquote(target.split("#", 1)[0])
            if not target_path:
                continue
            resolved = (path.parent / target_path).resolve()
            if not resolved.exists():
                errors.append(f"{path.relative_to(root)}:{line_number}: missing local link {target}")
    return errors


def check_release_links(readme: Path) -> list[str]:
    """README 中的 Release 下载链接必须真实可下载（发布前联网检查，默认不启用）。"""
    errors: list[str] = []
    urls = sorted(set(re.findall(r"https://[^\s)]+/releases/download/[^\s)]+", readme.read_text(encoding="utf-8"))))
    for url in urls:
        try:
            request = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(request, timeout=20) as response:
                if response.status != 200:
                    errors.append(f"{url}: HTTP {response.status}")
        except urllib.error.URLError as error:
            errors.append(f"{url}: {error}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--check-release-links",
        action="store_true",
        help="联网请求 README.md 里的 releases/download 链接，发版后、推送 README 前使用",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    if args.check_release_links:
        errors = check_release_links(root / "README.md")
        if errors:
            print("README release link check failed:")
            print("\n".join(errors))
            return 1
        print("README release links reachable")
    checked_paths = [
        path for path in iter_markdown(root)
        if "docs/archive" not in path.relative_to(root).as_posix()
        and "docs/work" not in path.relative_to(root).as_posix()
    ]
    errors = [error for path in checked_paths for error in check_file(path, root)]
    if errors:
        print("Markdown link check failed:")
        print("\n".join(errors))
        return 1
    print(f"Markdown link check passed: {len(checked_paths)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
