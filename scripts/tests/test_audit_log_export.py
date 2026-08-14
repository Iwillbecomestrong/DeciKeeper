#!/usr/bin/env python3
"""Tests for scripts/audit_log_export.py — Boundary 0 GREEN gate + Round 3 audit.

Run with:
    cd G:\\IronWIl0329\\IronWill\\.claude\\worktrees\\agent-ad1f5c7db01030ea9
    python -m unittest scripts.tests.test_audit_log_export
"""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest


# Make the sibling `scripts/audit_log_export.py` importable regardless of CWD.
THIS_DIR = os.path.dirname(os.path.abspath(__file__))
SCRIPTS_DIR = os.path.dirname(THIS_DIR)
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import audit_log_export as aul  # noqa: E402  (path-adjusted import)


FIXTURE_PATH = os.path.join(
    THIS_DIR,
    "..",
    "..",
    "docs",
    "superpowers",
    "specs",
    "2026-07-15-ironwill-penalty-reclock-logfix-references",
    "2026-07-15-device-log.txt",
)


def _load_fixture_lines():
    with open(FIXTURE_PATH, "r", encoding="utf-8", errors="replace") as fh:
        return fh.readlines()


class ParseExportTests(unittest.TestCase):
    def test_canonical_event_with_iso_timestamp_is_classified_as_canonical(self):
        line = "2026-07-15 13:01:39.687 [FG_DECISION] eventId=abc seq=1679"
        parsed = aul.parse_export([line])
        self.assertEqual(len(parsed), 1)
        p = parsed[0]
        self.assertEqual(p.timestamp, "2026-07-15 13:01:39.687")
        self.assertEqual(p.tag, "FG_DECISION")
        self.assertEqual(p.source, aul.SOURCE_IRONWILL_CANONICAL)

    def test_canonical_event_with_short_timestamp(self):
        line = "07-15 13:01:46.157 [TEMP_ACCESS_COOLDOWN_END] eventId=abc"
        parsed = aul.parse_export([line])
        self.assertEqual(parsed[0].timestamp, "07-15 13:01:46.157")
        self.assertEqual(parsed[0].tag, "TEMP_ACCESS_COOLDOWN_END")
        self.assertEqual(parsed[0].source, aul.SOURCE_IRONWILL_CANONICAL)

    def test_legacy_raw_lines_classify_as_legacy(self):
        legacy_cases = [
            ("07-15 13:01:42.450 AntiUninstall: [Event] Pkg=com.bbk.launcher2", aul.SOURCE_LEGACY_RAW),
            ("07-15 13:01:39.000 LockWindowManager: LockScreen: 解锁: reason=USER", aul.SOURCE_LEGACY_RAW),
            ("07-15 13:01:40.000 CreditManager: [TIME_CONSUME] amount=5 reason=LOCK", aul.SOURCE_LEGACY_RAW),
            ("07-15 13:02:00.000 CoreService: onServiceConnected", aul.SOURCE_LEGACY_RAW),
            ("07-15 13:02:01.000 MonitorService: [健康心跳] isRunning=true", aul.SOURCE_LEGACY_RAW),
            ("07-15 13:04:11.154 TemporaryAccess: [EXPIRY_LOCK] blacklist=com.tencent.mobileqq",
             aul.SOURCE_IRONWILL_CANONICAL),
            ("07-15 13:04:11.197 LockScreen: [DEFERRED] Device locked, deferring: reason=Blocked: com.tencent.mobileqq",
             aul.SOURCE_IRONWILL_CANONICAL),
        ]
        for raw, expected in legacy_cases:
            with self.subTest(raw=raw):
                src = aul.classify_source(raw)
                self.assertEqual(
                    src,
                    expected,
                    f"expected {expected} classification for {raw!r}",
                )

    def test_header_and_footer_skip_to_header_footer_bucket(self):
        for raw in [
            "=== IRONWILL INTERNAL LOG (591 lines (最近 300s)) ===",
            "=== END INTERNAL LOG ===",
        ]:
            with self.subTest(raw=raw):
                self.assertEqual(aul.classify_source(raw), aul.SOURCE_HEADER_FOOTER)

    def test_info_filtered_line_is_info_source(self):
        line = "[INFO] Filtered: kept 591 of 36549 lines"
        self.assertEqual(aul.classify_source(line), aul.SOURCE_INFO)

    def test_unknown_line_falls_through(self):
        line = "garbage that matches nothing"
        self.assertEqual(aul.classify_source(line), aul.SOURCE_UNKNOWN)

    def test_blank_lines_are_ignored(self):
        parsed = aul.parse_export(["", "   ", "\n"])
        self.assertEqual(parsed, [])


class ClassifySourceTests(unittest.TestCase):
    def test_canonical_tag_in_known_set(self):
        self.assertEqual(
            aul.classify_source("2026-07-15 13:01:46.175 [LOCK_RESULT] eventId=x"),
            aul.SOURCE_IRONWILL_CANONICAL,
        )

    def test_canonical_tag_not_in_known_set_is_pid_line(self):
        self.assertEqual(
            aul.classify_source("2026-07-15 13:01:39.000 [CUSTOM_BLOCK] eventId=x"),
            aul.SOURCE_IRONWILL_PID_LINE,
        )


class SummarizeTests(unittest.TestCase):
    def test_summarize_real_591_line_fixture(self):
        lines = _load_fixture_lines()
        summary = aul.summarize(lines, source_file=FIXTURE_PATH)

        self.assertEqual(summary["declaredKept"], 591)
        self.assertEqual(summary["declaredTotal"], 36549)
        self.assertEqual(summary["totalKept"], 591)
        self.assertGreater(summary["ironWillPidCount"], 0)

        tags = {t["tag"] for t in summary["topTags"]}
        for required in ("FG_DECISION", "EXPIRY_LOCK", "DEFERRED", "PENALTY"):
            self.assertIn(required, tags, f"{required} missing from topTags")

        tag_counts = {t["tag"]: t["count"] for t in summary["topTags"]}
        self.assertEqual(tag_counts["EXPIRY_LOCK"], 111)
        self.assertEqual(tag_counts["DEFERRED"], 113)

        self.assertEqual(summary["duplicateKeys"], [])

    def test_summarize_is_deterministic(self):
        lines = _load_fixture_lines()
        a = aul.summarize(lines, source_file=FIXTURE_PATH)
        b = aul.summarize(lines, source_file=FIXTURE_PATH)
        self.assertEqual(
            json.dumps(a, sort_keys=True, ensure_ascii=False),
            json.dumps(b, sort_keys=True, ensure_ascii=False),
        )

    def test_summarize_top_n_truncates(self):
        lines = _load_fixture_lines()
        small = aul.summarize(lines, source_file="x", top_n=3)
        self.assertLessEqual(len(small["topTags"]), 3)

    def test_summarize_handles_minimal_input(self):
        summary = aul.summarize(["hello"], source_file="<synthetic>")
        self.assertEqual(summary["totalKept"], 1)
        self.assertEqual(summary["sourceCounts"], {aul.SOURCE_UNKNOWN: 1})
        self.assertEqual(summary["topTags"], [])
        self.assertEqual(summary["duplicateKeys"], [])

    def test_duplicate_keys_detected_within_single_line(self):
        line = "2026-07-15 13:01:00.000 [LOCK_EXIT] evidence=DESKTOP_NOT_CONFIRMED evidence=EVENTS"
        summary = aul.summarize([line], source_file="<synthetic>")
        self.assertTrue(
            any("evidence" in d for d in summary["duplicateKeys"]),
            f"expected 'evidence' duplicate, got {summary['duplicateKeys']}",
        )

    def test_main_writes_json_to_stdout_and_returns_zero(self):
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as fh:
            # 1. canonical event line (NORMAL_EVENT disposition)
            fh.write("2026-07-15 13:01:39.687 [FG_DECISION] eventId=x seq=1\n")
            # 2. canonical atomic-block continuation (NORMAL_EVENT)
            fh.write("[1 结果] foreground=抖音\n")
            # 3. legacy FG_BYPASS_DIAG (DEBUG_ONLY)
            fh.write("07-15 13:01:39.400 MonitorService: [FG_BYPASS_DIAG] skip=true pkg=微信\n")
            # 4. legacy [DETECT] (REMOVE)
            fh.write("07-13 08:30:00.100 CoreService: [DETECT] Blacklist app: pkg=抖音\n")
            # 5. out-of-scope AntiUninstall system event
            fh.write("07-15 13:01:42.450 AntiUninstall: [Event] Pkg=p\n")
            # 6. header line
            fh.write("=== IRONWILL INTERNAL LOG (5 lines) ===\n")
            # 7. info filter line
            fh.write("[INFO] Filtered: kept 5 of 5 lines\n")
            # 8. footer line
            fh.write("=== END INTERNAL LOG ===\n")
            path = fh.name

        old_stdout = sys.stdout
        buf = io.StringIO()
        try:
            sys.stdout = buf
            rc = aul.main(["audit_log_export.py", path])
        finally:
            sys.stdout = old_stdout
        os.unlink(path)
        self.assertEqual(rc, 0, "all 7 lines are NORMAL_EVENT/DEBUG_ONLY/REMOVE/OUT_OF_SCOPE")
        out = buf.getvalue()
        data = json.loads(out)
        self.assertIn("summaries", data)
        self.assertEqual(len(data["summaries"]), 1)
        # round3 unclassified must be empty
        self.assertEqual(data["round3"][0]["unclassified"], [])

    def test_main_returns_2_when_no_args(self):
        old_stderr = sys.stderr
        buf = io.StringIO()
        try:
            sys.stderr = buf
            rc = aul.main(["audit_log_export.py"])
        finally:
            sys.stderr = old_stderr
        self.assertEqual(rc, 2)


class FixtureMirrorContractTests(unittest.TestCase):
    """The JVM `LogSourceDistributionTest` reads `scripts/tests/fixtures/audit_summary.json`.
    This test enforces that file's contract — emit + reload round-trip."""

    FIXTURE_JSON = os.path.join(THIS_DIR, "fixtures", "audit_summary.json")

    def test_fixture_json_matches_audit_output_when_regenerated(self):
        if not os.path.isfile(FIXTURE_PATH):
            self.skipTest("591-line fixture missing")
        lines = _load_fixture_lines()
        summary = aul.summarize(lines, source_file=FIXTURE_PATH)
        normalized = dict(summary)
        normalized["sourceFile"] = (
            "docs/superpowers/specs/2026-07-15-ironwill-penalty-reclock-logfix-references/"
            "2026-07-15-device-log.txt"
        )
        if os.path.isfile(self.FIXTURE_JSON):
            with open(self.FIXTURE_JSON, "r", encoding="utf-8") as fh:
                committed = json.load(fh)
            self.assertEqual(
                json.dumps(committed, sort_keys=True, ensure_ascii=False),
                json.dumps(normalized, sort_keys=True, ensure_ascii=False),
                "fixture JSON is out of date — regenerate via scripts/audit_log_export.py",
            )
        else:
            os.makedirs(os.path.dirname(self.FIXTURE_JSON), exist_ok=True)
            with open(self.FIXTURE_JSON, "w", encoding="utf-8") as fh:
                json.dump(normalized, fh, ensure_ascii=False, indent=2)
                fh.write("\n")
            self.skipTest(
                f"wrote initial fixture to {self.FIXTURE_JSON}; "
                "re-run this test once it is committed to confirm round-trip"
            )


class Round3AuditTests(unittest.TestCase):
    """Round 3 (boundary 0.5) Debug-Value Audit disposition classifier."""

    def test_normal_event_iso_full_tag(self):
        line = "2026-07-15 13:01:39.687 [FG_DECISION] eventId=abc seq=1679"
        self.assertEqual(
            aul.classify_source_audit_round3(line),
            aul.DISPOSITION_NORMAL_EVENT,
        )

    def test_normal_event_short_timestamp_temp_event(self):
        line = "07-15 13:01:46.157 [TEMP_ACCESS_COOLDOWN_END] eventId=abc"
        self.assertEqual(
            aul.classify_source_audit_round3(line),
            aul.DISPOSITION_NORMAL_EVENT,
        )

    def test_normal_event_storm_evidence_via_logexporter(self):
        # The C-B1-10 storm evidence line in the 591-line fixture (no eventId suffix
        # but emitted through `LogExporter.logEvent` -> NORMAL_EVENT bucket).
        line = ("07-15 13:04:11.197 LockScreen: [DEFERRED] Device locked, deferring: "
                "reason=Blocked: com.tencent.mobileqq")
        self.assertEqual(
            aul.classify_source_audit_round3(line),
            aul.DISPOSITION_NORMAL_EVENT,
        )

    def test_debug_only_vis_check_diag(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.600 MonitorService: [VIS_CHECK_DIAG] pkg=抖音 visible=true"
            ),
            aul.DISPOSITION_DEBUG_ONLY,
        )

    def test_debug_only_fg_bypass_diag(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.400 MonitorService: [FG_BYPASS_DIAG] skip=true reason=WHITELIST pkg=微信"
            ),
            aul.DISPOSITION_DEBUG_ONLY,
        )

    def test_debug_only_should_block_diag(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.450 MonitorService: [SHOULD_BLOCK_DIAG] should=false pkg=微信"
            ),
            aul.DISPOSITION_DEBUG_ONLY,
        )

    def test_debug_only_fg_guard_diag(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.520 MonitorService: [FG_GUARD_DIAG] candidate=抖音 guard=PASS"
            ),
            aul.DISPOSITION_DEBUG_ONLY,
        )

    def test_debug_only_time_consume_quota_gated(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-15 13:01:40.000 CreditManager: [TIME_CONSUME] amount=5 reason=LOCK"
            ),
            aul.DISPOSITION_DEBUG_ONLY,
        )

    def test_debug_only_step123(self):
        for n in ("1", "2", "3"):
            with self.subTest(step=n):
                line = f"07-15 13:01:4{n}.000 CreditManager: [STEP{n}] penalty candidate pkg=抖音"
                self.assertEqual(
                    aul.classify_source_audit_round3(line),
                    aul.DISPOSITION_DEBUG_ONLY,
                )

    def test_remove_detect(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.100 CoreService: [DETECT] Blacklist app: pkg=com.ss.android.ugc.aweme"
            ),
            aul.DISPOSITION_REMOVE,
        )

    def test_remove_mini_window_fallback(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.200 CoreService: [MINI_WINDOW_FALLBACK] size=2 pkgs=[抖音, 微信]"
            ),
            aul.DISPOSITION_REMOVE,
        )

    def test_remove_event_app_state(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.250 MonitorService: [EVENT] type=APP_STATE state=FG pkg=抖音"
            ),
            aul.DISPOSITION_REMOVE,
        )

    def test_remove_fg_pkg(self):
        self.assertEqual(
            aul.classify_source_audit_round3(
                "07-13 08:30:00.300 MonitorService: FG pkg=抖音 state=allow confidence=HIGH summary=true"
            ),
            aul.DISPOSITION_REMOVE,
        )

    def test_remove_seven_line_evidence_block(self):
        for snippet in [
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 前台结论: 抖音 | 选用来源=EVENTS",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 Accessibility: 可用",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 UsageEvents: #1 抖音",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 UsageStats: #1 抖音",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 内部状态: lastPkg=抖音",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 判断理由: UsageEvents",
            "07-13 08:30:00.700 MonitorService: LOCK-20260713-083000-0001 锁机上下文: 锁定原因=BLACKLIST",
        ]:
            with self.subTest(snippet=snippet):
                self.assertEqual(
                    aul.classify_source_audit_round3(snippet),
                    aul.DISPOSITION_REMOVE,
                )

    def test_remove_lock_screen_lifecycle(self):
        cases = [
            "07-13 08:30:00.900 LockWindowManager: LockScreen: 解锁: reason=USER requestId=LOCK-001",
            "07-13 08:30:00.950 LockWindowManager: LockScreen: 🏠 回到桌面: pkg=抖音",
            "07-13 08:30:01.000 LockWindowManager: TempAccess: 临时解锁: pkg=抖音",
            "07-13 08:30:01.600 AlarmWindowManager: ring show=true pkg=抖音",
            "07-13 08:30:01.700 LockWindowManager: snooze action=60s pkg=抖音",
            "07-13 08:30:00.750 MonitorService: [锁机] Blacklist app: pkg=抖音",
            "07-13 08:30:00.800 CoreService: [RESTORE_REQUEST] pkg=com.ironwill.app",
            "07-13 08:30:00.850 MonitorService: [AUTO_RELEASE] Skip: target=com.ironwill.app",
            "══════ 每日状态播报 ══════",
            "07-13 08:30:01.400 AlarmHeartbeatWorker: 💓 心跳(worker) cycle=A",
            "07-13 08:30:02.050 CoreService: onServiceConnected",
            "07-13 08:30:02.060 CoreService: onUnbind reason=USER",
            "07-13 08:30:02.070 CoreService: onRebind",
            "07-13 08:30:02.080 LockWindowManager: TemporaryAccess: [FINAL_RELOCK_SUCCEEDED] cleared flag",
            "07-13 08:30:02.090 LockWindowManager: LockScreen: [REASSERT]",
        ]
        for line in cases:
            with self.subTest(line=line):
                self.assertEqual(
                    aul.classify_source_audit_round3(line),
                    aul.DISPOSITION_REMOVE,
                )

    def test_unclassified_line_is_unclassified(self):
        # Random noise that matches no classifier in the audit table
        self.assertEqual(
            aul.classify_source_audit_round3("07-15 13:01:00.000 RandomTag: noisy weird line"),
            aul.DISPOSITION_UNCLASSIFIED,
        )

    def test_audit_round3_zero_unclassified_on_591_fixture(self):
        if not os.path.isfile(FIXTURE_PATH):
            self.skipTest("591-line fixture missing")
        lines = _load_fixture_lines()
        report = aul.audit_round3(lines, source_file=FIXTURE_PATH)
        # The 591-line fixture is post-R3 compact export. Every IronWill-owned line
        # must classify into {NORMAL_EVENT, DEBUG_ONLY, REMOVE} or be flagged OUT_OF_SCOPE
        # (AntiUninstall/accessibility/header/footer/info). Anything else is a coverage
        # miss that the migration table must fill in.
        self.assertEqual(
            report["unclassified"],
            [],
            "Round 3 audit must leave zero unclassified lines on 591-line fixture, "
            f"got: {[u['line'][:80] for u in report['unclassified'][:5]]}",
        )
        # OUT_OF_SCOPE should at least include the AntiUninstall system events.
        self.assertGreater(
            report["dispositionCounts"].get(aul.DISPOSITION_OUT_OF_SCOPE, 0),
            0,
            "OUT_OF_SCOPE should capture AntiUninstall/accessibility/header lines",
        )

    def test_summarize_round3_embedded_key_present(self):
        lines = ["2026-07-15 13:01:39.687 [FG_DECISION] eventId=x\n"]
        summary = aul.summarize(lines, source_file="<synthetic>")
        self.assertIn("round3Audit", summary)
        self.assertIn("dispositionCounts", summary["round3Audit"])
        self.assertIn("unclassified", summary["round3Audit"])

    def test_round3_fixture_matches_audit_output(self):
        """Round 3 (boundary 0.5) fixture mirror contract — committed JSON must
        match what the live classifier emits on the 591-line fixture.
        """
        fixture_path = os.path.join(THIS_DIR, "fixtures", "audit_summary_round3.json")
        if not os.path.isfile(fixture_path):
            self.skipTest("Round 3 fixture missing")
        if not os.path.isfile(FIXTURE_PATH):
            self.skipTest("591-line fixture missing")
        lines = _load_fixture_lines()
        report = aul.audit_round3(lines, source_file=FIXTURE_PATH)
        normalized = dict(report)
        normalized["sourceFile"] = (
            "docs/superpowers/specs/2026-07-15-ironwill-penalty-reclock-logfix-references/"
            "2026-07-15-device-log.txt"
        )
        with open(fixture_path, "r", encoding="utf-8") as fh:
            committed = json.load(fh)
        self.assertEqual(
            json.dumps(committed, sort_keys=True, ensure_ascii=False),
            json.dumps(normalized, sort_keys=True, ensure_ascii=False),
            "audit_summary_round3.json is out of date — regenerate",
        )

    def test_round3_classifier_returns_valid_disposition(self):
        # Quick sanity that the Round 3 audit enum is exactly what the table uses.
        self.assertEqual(
            aul.DISPOSITIONS,
            frozenset({"NORMAL_EVENT", "DEBUG_ONLY", "REMOVE"}),
        )

    def test_round3_classifier_rejects_parallel_required_optional_removable(self):
        # Plan Challenger v3 A1: only NORMAL_EVENT / DEBUG_ONLY / REMOVE allowed.
        for raw_line in [
            "07-15 13:01:42.450 something_with_REQUIRED_marker",
            "07-15 13:01:42.450 something_with_OPTIONAL_marker",
            "07-15 13:01:42.450 something_with_REMOVABLE_marker",
        ]:
            d = aul.classify_source_audit_round3(raw_line)
            # None of these lines should happen to be classified NORMAL_EVENT,
            # DEBUG_ONLY, or REMOVE based on label alone.
            self.assertEqual(
                d,
                aul.DISPOSITION_UNCLASSIFIED,
                f"line {raw_line!r} unexpectedly classified as {d}",
            )


if __name__ == "__main__":
    unittest.main()
