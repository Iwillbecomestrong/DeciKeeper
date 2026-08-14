#!/usr/bin/env python3
"""IronWill log export audit tool — Boundary 0 baseline + Round 3 (boundary 0.5).

Parses the 591-line IronWill internal log fixture (and any compatible export) and emits
a deterministic JSON summary describing:

    - total kept lines vs. header-declared count
    - per-source classification (IronWill canonical event / system AntiUninstall /
      CreditManager / MonitorService legacy / LockWindowManager legacy / etc.)
    - top-N canonical event tags (sorted by descending count, tie-broken by tag name)
    - duplicate `key=` evidence tokens (canonicalizer invariant)
    - Round 3 (boundary 0.5) Debug-Value Audit disposition classifier
      (`NORMAL_EVENT` / `DEBUG_ONLY` / `REMOVE` for every observable log line)

Public surface (kept small for testability):
    parse_export(lines) -> ParsedLine
    classify_source(line) -> str (one of SOURCE_* constants)
    summarize(lines, *, source_file, top_n) -> dict
    classify_source_audit_round3(line) -> str (one of `NORMAL_EVENT`, `DEBUG_ONLY`, `REMOVE`)
    audit_round3(lines) -> dict (per-line disposition counts + unclassified label list)
    main(argv) -> int

Determinism: no `time.time()`, no `os.urandom()`, no `random.*` in any code path
that influences the output. Two calls with the same input return byte-identical JSON.

Usage:
    python scripts/audit_log_export.py <input.txt> [<input.txt> ...]

Prints the JSON summary to stdout. Exit code 0 on success, 1 on parse error or
unclassified labels (Round 3 governance).
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from typing import Dict, Iterable, List, NamedTuple, Sequence


# --- Public constants ---------------------------------------------------------

# Canonical event tag pattern. The 591-line fixture uses two forms:
#   2026-07-15 13:01:39.687 [FG_DECISION] eventId=...           <- NormalEventLogger output
#   07-15 13:01:46.157 [TEMP_ACCESS_COOLDOWN_END] eventId=...   <- NormalEventLogger output
#   07-15 13:04:11.154 TemporaryAccess: [EXPIRY_LOCK] blacklist=... <- LogExporter.logEvent output
#   07-15 13:04:11.197 LockScreen: [DEFERRED] Device locked, ...  <- LogExporter.logEvent output
NORMAL_EVENT_HEADER = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}|\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})"
    r"(?:\s+(?P<source>[A-Za-z][A-Za-z0-9_]*):)?"
    r"\s+\[(?P<tag>[A-Z][A-Z0-9_]*)\]"
)

# AntiUninstall / MonitorService / LockWindowManager / CreditManager raw lines (legacy)
LEGACY_LINE = re.compile(
    r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+"
    r"(?P<source>AntiUninstall|MonitorService|LockWindowManager|CreditManager|"
    r"LockScreen|TemporaryAccess|TempAccess|DesktopTimer|"
    r"AlarmHeartbeatWorker|AlarmGuardianService|"
    r"AlarmWindowManager|CoreService|"
    r"PresenceNotificationListener):"
    r"(?!\s*\[(?:FG_DECISION|TEMP_ACCESS_EXPIRY|TEMP_ACCESS_COOLDOWN_END|TEMP_ACCESS_START|"
    r"TEMP_ACCESS_END|FG_END|FOREGROUND_TRANSITION|LOCK_RESULT|LOCK_EXIT|"
    r"EMERGENCY_ACCESS_START|EMERGENCY_ACCESS_END|REST_QUOTA_CHANGE|PENALTY|"
    r"PENALTY_EVALUATION|PENALTY_APPLIED|PROTECTION_STATE_CHANGE|ALARM_TRIGGER|"
    r"BLACKLIST_ENTRY|LOCK_RETRY_STORM)\])"
)

# Header / footer / structural lines
HEADER_LINE = re.compile(r"^===\s+IRONWILL INTERNAL LOG")
FOOTER_LINE = re.compile(r"^===\s+END INTERNAL LOG")
FILTERED_LINE = re.compile(r"^\[INFO\]\s+Filtered:\s+kept\s+(?P<kept>\d+)\s+of\s+(?P<total>\d+)\s+lines")

# Evidence token (key=value) extractor — used for the duplicate-keys invariant.
EVIDENCE_KEY = re.compile(r"(?<![\w.])(\w+)=(?=\S)")

# Source classification buckets (exhaustive — new buckets must be added explicitly).
SOURCE_IRONWILL_CANONICAL = "ironwill_canonical_event"
SOURCE_IRONWILL_PID_LINE = "ironwill_pid_line"  # lines that carry `processInstanceId=...` (canonical block continuation)
SOURCE_LEGACY_RAW = "ironwill_legacy_raw"
SOURCE_SYSTEM_EVENT = "system_event"            # AntiUninstall / accessibility events
SOURCE_HEADER_FOOTER = "header_footer"
SOURCE_INFO = "info"
SOURCE_UNKNOWN = "unknown"

# Recognized canonical tags for the topTags section. Anything not in this set is bucketed
# under `ironwill_pid_line` (canonical block continuation / structural) and does not show
# up in `topTags` (debug-noise filter).
CANONICAL_TAGS = frozenset({
    "FG_DECISION",
    "FG_END",
    "FOREGROUND_TRANSITION",
    "LOCK_RESULT",
    "LOCK_EXIT",
    "TEMP_ACCESS_START",
    "TEMP_ACCESS_END",
    "TEMP_ACCESS_COOLDOWN_END",
    "EXPIRY_LOCK",
    "DEFERRED",
    "EMERGENCY_ACCESS_START",
    "EMERGENCY_ACCESS_END",
    "EMERGENCY_RELOCK",
    "REST_QUOTA_CHANGE",
    "PENALTY",
    "PENALTY_EVALUATION",
    "PENALTY_APPLIED",
    "PROTECTION_STATE_CHANGE",
    "ALARM_TRIGGER",
    "BLACKLIST_ENTRY",
    "TEMP_ACCESS_EXPIRY",
    "TEMP_ACCESS_EXPIRY_DUPLICATE",
    "LOCK_RETRY_STORM",
    "COOLDOWN_START",
    "CLEAR",
    "OK",
})

# Tags that match the canonical header regex but are STILL legacy (must NOT enter topTags).
LEGACY_TAGS = frozenset({
    "DETECT",
    "MINI_WINDOW_FALLBACK",
    "FG_BYPASS_DIAG",
    "SHOULD_BLOCK_DIAG",
    "FG_GUARD_DIAG",
    "VIS_CHECK_DIAG",
    "FG_SUMMARY",
    "BORROW_UI_DEBUG",
    "TIME_CONSUME",
    "STEP1",
    "STEP2",
    "STEP3",
    "FINAL_RELOCK_SUCCEEDED",
    "REASSERT",
})


# --- Round 3 (boundary 0.5) Debug-Value Audit classifier ----------------------

# Round 3 audit dispositions (must match `docs/ai/LOG_EVENT_MIGRATION.md` table).
DISPOSITION_NORMAL_EVENT = "NORMAL_EVENT"
DISPOSITION_DEBUG_ONLY = "DEBUG_ONLY"
DISPOSITION_REMOVE = "REMOVE"
DISPOSITION_UNCLASSIFIED = "UNCLASSIFIED"
DISPOSITION_OUT_OF_SCOPE = "OUT_OF_SCOPE"

DISPOSITIONS = frozenset({DISPOSITION_NORMAL_EVENT, DISPOSITION_DEBUG_ONLY, DISPOSITION_REMOVE})

# Patterns whose match declares the line `NORMAL_EVENT` (a canonical event tag,
# already governed by NormalEventLog / LogExporter.logEvent).
# Order matters: more-specific patterns first.
_NORMAL_EVENT_PATTERNS = [
    # Canonical-event HEADER line — emitted by NormalEventLogger in two timestamp
    # forms (full `2026-07-15 13:01:39.687` for FG_DECISION/LOCK_RESULT/etc., and the
    # shorter `07-15 13:01:46.157` variant for TEMP_ACCESS_*). Both carry `eventId=`.
    re.compile(r"^(?:\d{4}-\d{2}-\d{2}|\d{2}-\d{2}) \d{2}:\d{2}:\d{2}\.\d{3}\s+"
               r"\[(?:FG_DECISION|FG_END|FOREGROUND_TRANSITION|LOCK_RESULT|LOCK_EXIT|"
               r"TEMP_ACCESS_START|TEMP_ACCESS_END|TEMP_ACCESS_COOLDOWN_END|"
               r"EMERGENCY_ACCESS_START|EMERGENCY_ACCESS_END|EMERGENCY_RELOCK|"
               r"REST_QUOTA_CHANGE|PENALTY|PENALTY_EVALUATION|PENALTY_APPLIED|"
               r"PROTECTION_STATE_CHANGE|ALARM_TRIGGER|BLACKLIST_ENTRY|"
               r"TEMP_ACCESS_EXPIRY|TEMP_ACCESS_EXPIRY_DUPLICATE|LOCK_RETRY_STORM|"
               r"COOLDOWN_START|CLEAR|OK|SERVICE_STATE)\]\s+eventId="),
    # EXPIRY_LOCK / DEFERRED storm-evidence events AND other `LogExporter.logEvent`
    # emissions that use the short timestamp + `Source:` form (no `eventId=` suffix).
    # Examples in fixture 591:
    #   07-15 13:04:11.197 LockScreen: [DEFERRED] Device locked, deferring: ...
    #   07-15 13:03:18.845 TemporaryAccess: [COOLDOWN_START] reuseWaitUntil=...
    #   07-15 13:03:33.508 TemporaryAccess: [EMERGENCY] 10s free access, ...
    #   07-15 13:03:43.509 TemporaryAccess: [EMERGENCY_RELOCK] still on blacklist, ...
    #   07-15 13:03:41.271 A11ySelfDisableAuth: [CLEAR] reason=...
    #   07-15 13:03:42.473 PermissionManager: [OK] Permission restored: ...
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+[A-Za-z][A-Za-z0-9_]*:\s+"
               r"\[(?:EXPIRY_LOCK|DEFERRED|EMERGENCY_RELOCK|EMERGENCY|COOLDOWN_START|CLEAR|OK)\]"),
    # Continuation lines of a NormalEvent atomic block (DefaultNormalEventRenderer output).
    # Pattern: `[<digit> <text>]` right after a header line. Examples in fixture 591:
    #   [1 结果] foreground=抖音 ...
    #   [3 UsageEvents] status=NO_DATA ...
    #   [5 Accessibility] status=AVAILABLE ...
    #   [7 锁屏] requestId=...
    re.compile(r"^\[\d+\s+[^\]]+\]"),
]

# Patterns whose match declares the line `DEBUG_ONLY` — debug-gated DIAG lines that
# are guarded by `DefaultLogDebugConfig.foregroundRaw` / `quotaCalculation`.
# These remain in production but emit nothing in default builds.
_DEBUG_ONLY_PATTERNS = [
    re.compile(r"\[VIS_CHECK_DIAG\]"),
    re.compile(r"\[VIS_DECISION\]"),
    re.compile(r"\[FG_BYPASS_DIAG\]"),
    re.compile(r"\[SHOULD_BLOCK_DIAG\]"),
    re.compile(r"\[FG_GUARD_DIAG\]"),
    # [BORROW_UI_DEBUG] / [TIME_CONSUME] / [STEP1]/[STEP2]/[STEP3] — quotaCalculation-gated
    re.compile(r"\[BORROW_UI_DEBUG\]"),
    re.compile(r"\[TIME_CONSUME\]"),
    re.compile(r"\[STEP[123]\]"),
]

# Patterns whose match declares the line `REMOVE`. These lines are already dropped
# by an `exportDropPatterns` entry at the export sink (Tasks 1-5) and never appear
# in the canonical exported fixture after R3. A `REMOVE` row in the table is the
# commitment that no future code resurrection is allowed.
_REMOVE_PATTERNS = [
    re.compile(r"\[DETECT\]"),
    re.compile(r"\[MINI_WINDOW_FALLBACK\]"),
    re.compile(r"\[EVENT\]\s+type=APP_STATE"),
    re.compile(r"^[^|]*FG pkg="),
    re.compile(r"FG_SUMMARY"),
    re.compile(r"\[EVENT\]\s+type=NAVIGATION"),
    re.compile(r"\[EVENT\]\s+type=BLOCK_DECISION"),
    re.compile(r"\[EVENT\]\s+type=VIS_DECISION"),
    # Legacy [EVENT] type=FG_DECISION from `EventLog.fgDecision(...)` — REQ-011 retired
    re.compile(r"\[EVENT\]\s+type=FG_DECISION"),
    re.compile(r"\[锁机\]"),
    re.compile(r"\[RESTORE_REQUEST\]"),
    re.compile(r"\[AUTO_RELEASE\]"),
    re.compile(r"前台结论:"),
    re.compile(r"^[^|]*Accessibility:\s+(?:可用|不可用)"),
    re.compile(r"UsageEvents:\s+#1"),
    re.compile(r"UsageStats:\s+#1"),
    re.compile(r"内部状态:"),
    re.compile(r"判断理由:"),
    re.compile(r"锁机上下文:"),
    re.compile(r"LockScreen:\s+解锁:"),
    re.compile(r"LockScreen:\s+🏠\s+回到桌面:"),
    re.compile(r"TempAccess:\s+临时解锁:"),
    re.compile(r"\[DONE\]\s+penalty applied"),
    re.compile(r"══════\s+每日状态播报\s+══════"),
    re.compile(r"💓\s+心跳"),
    re.compile(r"AlarmWindowManager:\s+ring show=true"),
    re.compile(r"LockWindowManager:\s+snooze action="),
    re.compile(r"CoreService:\s+onServiceConnected"),
    re.compile(r"CoreService:\s+onUnbind"),
    re.compile(r"CoreService:\s+onRebind"),
    re.compile(r"TemporaryAccess:\s+\[FINAL_RELOCK_SUCCEEDED\]\s+cleared flag"),
    re.compile(r"LockScreen:\s+\[REASSERT\]"),
    # REQ-010 retired: LockWindowManager.kt:3471 Chinese `Penalty: 惩罚:` raw lines.
    # The source prefix (`^[^|]*Penalty:\s+`) precedes the (encoding-mangled) Chinese
    # fragment; works whether the fixture is read with utf-8 or with errors=replace.
    re.compile(r"^[^|]*Penalty:\s+"),
]

# Patterns whose match declares the line `OUT_OF_SCOPE` for Round 3 — system events
# emitted by AntiUninstall / presence / accessibility machinery that the IronWill log
# migration table does not own.
_OUT_OF_SCOPE_PATTERNS = [
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+AntiUninstall:\s+\[Event\]"),
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+PresenceNotificationListener:"),
    re.compile(r"^===\s+IRONWILL INTERNAL LOG"),
    re.compile(r"^===\s+END INTERNAL LOG"),
    re.compile(r"^\[INFO\]\s+Filtered:"),
    # Internal IronWill lifecycle/status lines that the Round 3 migration table does
    # not own (one-off heartbeat / timer-thread / desktop-timer status reports).
    # Reading the fixture with `errors=replace` mangles Chinese labels to `?`, so we
    # match on the source tag, which is always ASCII.
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+DesktopTimer:"),
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+Timer:\s+\[THREAD\]"),
    # `MonitorService: [XXX]` lines with Chinese labels whose encoding via
    # `errors=replace` may show `?`. We catch both formats. Carriers / heartbeat /
    # diagnostic state lines that the migration table does not own.
    re.compile(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+MonitorService:\s+\[[^\]]+\]"),
]


def classify_source_audit_round3(line: str) -> str:
    """Map one raw log line to its Round 3 Debug-Value Audit disposition.

    Returns one of:
        NORMAL_EVENT  — a canonical event worth keeping without retention
        DEBUG_ONLY    — debug-gated (emitted only when foregroundRaw / quotaCalculation
                        is on; default off)
        REMOVE        — production emission dropped by `exportDropPatterns` already;
                        must remain dropped
        UNCLASSIFIED  — does not match any of the three dispositions

    The function is a pure string match over `line` (no time, no randomness) and is the
    trust anchor for `audit_round3` and for the JVM test that checks the migration table.
    """
    if not line or not line.strip():
        return DISPOSITION_UNCLASSIFIED

    for pat in _NORMAL_EVENT_PATTERNS:
        if pat.search(line):
            return DISPOSITION_NORMAL_EVENT
    for pat in _DEBUG_ONLY_PATTERNS:
        if pat.search(line):
            return DISPOSITION_DEBUG_ONLY
    for pat in _REMOVE_PATTERNS:
        if pat.search(line):
            return DISPOSITION_REMOVE
    for pat in _OUT_OF_SCOPE_PATTERNS:
        if pat.search(line):
            return DISPOSITION_OUT_OF_SCOPE
    return DISPOSITION_UNCLASSIFIED


def audit_round3(lines: Sequence[str], *, source_file: str = "") -> Dict[str, object]:
    """Classify every line through `classify_source_audit_round3` and aggregate.

    The result is a pure function of `lines` + `source_file`. Two calls with identical
    arguments MUST produce byte-identical JSON.

    Round 3 governance:
        * `unclassified` MUST be empty on a healthy, audit-ready export; non-empty means
          `classify_source_audit_round3` missed a label and the migration table needs a new row.
        * `dispositionCounts` is a stable dict of disposition -> line count (sorted by key).
    """
    counts: Counter[str] = Counter()
    unclassified: List[Dict[str, str]] = []

    for raw in lines:
        line = raw.rstrip("\n")
        disposition = classify_source_audit_round3(line)
        counts[disposition] += 1
        if disposition == DISPOSITION_UNCLASSIFIED and line.strip():
            unclassified.append({"source": source_file, "line": line})

    return {
        "sourceFile": source_file,
        "totalLines": len(list(lines)),
        "dispositionCounts": dict(sorted(counts.items())),
        "unclassified": unclassified,
    }


# --- Public types -------------------------------------------------------------

class ParsedLine(NamedTuple):
    raw: str
    timestamp: str
    tag: str
    source: str  # one of SOURCE_*


# --- Public API ----------------------------------------------------------------

def parse_export(lines: Iterable[str]) -> List[ParsedLine]:
    """Parse each line of an IronWill internal log export into a deterministic
    `(timestamp, tag, source)` triple. Blank lines and header/footer lines are skipped
    with `source == SOURCE_HEADER_FOOTER`. Lines that fail to match either the canonical
    header regex or the legacy regex fall into `SOURCE_UNKNOWN`.
    """
    out: List[ParsedLine] = []
    for raw in lines:
        line = raw.rstrip("\n")
        if not line.strip():
            continue
        if HEADER_LINE.match(line) or FOOTER_LINE.match(line):
            out.append(ParsedLine(line, "", "", SOURCE_HEADER_FOOTER))
            continue
        if "[INFO]" in line and "Filtered" in line:
            out.append(ParsedLine(line, "", "", SOURCE_INFO))
            continue

        m = NORMAL_EVENT_HEADER.match(line)
        if m:
            tag = m.group("tag")
            if tag in LEGACY_TAGS:
                out.append(ParsedLine(line, "", "", SOURCE_LEGACY_RAW))
            elif tag in CANONICAL_TAGS:
                out.append(ParsedLine(line, m.group("ts"), tag, SOURCE_IRONWILL_CANONICAL))
            else:
                out.append(ParsedLine(line, m.group("ts"), tag, SOURCE_IRONWILL_PID_LINE))
            continue

        m2 = LEGACY_LINE.match(line)
        if m2:
            out.append(ParsedLine(line, "", "", SOURCE_LEGACY_RAW))
            continue

        out.append(ParsedLine(line, "", "", SOURCE_UNKNOWN))
    return out


def classify_source(line: str) -> str:
    """Convenience wrapper — classify a single raw line."""
    parsed = parse_export([line])
    return parsed[0].source if parsed else SOURCE_UNKNOWN


def summarize(
    lines: Sequence[str],
    *,
    source_file: str = "",
    top_n: int = 20,
) -> Dict[str, object]:
    """Build the JSON-serializable summary consumed by `LogSourceDistributionTest`.

    The result is a pure function of `lines` + `source_file` + `top_n`; it never reads
    the wall clock, the OS entropy pool, or any external file. Two calls with the same
    arguments MUST produce byte-identical JSON.

    `totalKept` matches the device-export contract: it counts every non-blank line
    **excluding** the `=== IRONWILL INTERNAL LOG ===` / `=== END INTERNAL LOG ===`
    markers. The `[INFO] Filtered: kept X of Y lines` line is included in the count
    (it is part of the export), and that `X` value is what we cross-check against
    `totalKept`.
    """
    parsed = parse_export(lines)

    # Header sanity check: if the file declares "[INFO] Filtered: kept X of Y lines",
    # X must equal our `totalKept` (everything except the structural `===` markers).
    declared_kept: int | None = None
    declared_total: int | None = None
    for p in parsed:
        if p.source == SOURCE_INFO:
            m = FILTERED_LINE.match(p.raw)
            if m:
                declared_kept = int(m.group("kept"))
                declared_total = int(m.group("total"))

    countable = [p for p in parsed if p.source != SOURCE_HEADER_FOOTER]
    total_kept = len(countable)

    source_counts = Counter(p.source for p in countable)
    ironwill_pid_count = sum(
        1 for p in countable
        if p.source in (SOURCE_IRONWILL_CANONICAL, SOURCE_IRONWILL_PID_LINE)
    )

    # Top-N canonical event tags (descending count, then tag name ascending for stability).
    tag_counter: Counter[str] = Counter()
    for p in countable:
        if p.source == SOURCE_IRONWILL_CANONICAL and p.tag:
            tag_counter[p.tag] += 1
    top_tags = [
        {"tag": t, "count": c}
        for t, c in sorted(tag_counter.items(), key=lambda kv: (-kv[1], kv[0]))[:top_n]
    ]

    # duplicateKeys: any canonical-event HEADER line where a top-level `key=...`
    # token appears more than once. This is the canonicalizer invariant we care about
    # (the `canonicalizeEvidence` rule in LockWindowManager.kt must collapse such
    # duplicates within a single canonical event). We deliberately ignore continuation
    # lines like `[5 Accessibility] prev={...} curr={...}` where repeating `eventAtMs=`
    # / `handledAtMs=` etc. is structurally correct (prev/curr pairing).
    duplicate_keys: List[str] = []
    for p in countable:
        if p.source != SOURCE_IRONWILL_CANONICAL:
            continue
        keys = EVIDENCE_KEY.findall(p.raw)
        seen: set[str] = set()
        dups: set[str] = set()
        for k in keys:
            if k in seen:
                dups.add(k)
            seen.add(k)
        for k in sorted(dups):
            duplicate_keys.append(f"{p.source}::{p.tag}::{k}")

    return {
        "sourceFile": source_file,
        "totalKept": total_kept,
        "declaredKept": declared_kept,
        "declaredTotal": declared_total,
        "ironWillPidCount": ironwill_pid_count,
        "sourceCounts": dict(sorted(source_counts.items())),
        "topTags": top_tags,
        "duplicateKeys": duplicate_keys,
        # Round 3 (boundary 0.5) Debug-Value Audit classifier — embedded so the JVM
        # test can pull `dispositionCounts` and `unclassified` from the same payload.
        "round3Audit": audit_round3(lines, source_file=source_file),
    }


def main(argv: Sequence[str]) -> int:
    if len(argv) < 2:
        print("usage: audit_log_export.py <input.txt> [<input.txt> ...]", file=sys.stderr)
        return 2
    payload = {
        "summaries": [],
        "round3": [],
    }
    failed_round3 = False
    for path in argv[1:]:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
        payload["summaries"].append(summarize(lines, source_file=path))
        round3 = audit_round3(lines, source_file=path)
        if round3["unclassified"]:
            failed_round3 = True
        payload["round3"].append(round3)
    json.dump(payload, sys.stdout, ensure_ascii=False, indent=2, sort_keys=False)
    sys.stdout.write("\n")
    return 1 if failed_round3 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
