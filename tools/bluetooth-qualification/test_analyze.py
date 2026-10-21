"""Synthetic arithmetic/provenance tests; no physical or Android qualification."""

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SPEC = importlib.util.spec_from_file_location("offset_analysis", HERE / "analyze.py")
ANALYSIS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYSIS)


def synthetic():
    return {
        "schema_version": 1,
        "record_id": "SYNTHETIC-unit-test",
        "execution_status": "OBSERVED",
        "evidence_kind": "synthetic",
        "source": {"commit": None, "artifact_sha256": None, "fixture_sha256": None,
                   "source_clock_id": "SYNTHETIC-source"},
        "capture": {"capture_id": "SYNTHETIC-capture", "method": "SYNTHETIC edge values",
                    "raw_artifacts": [], "audio_clock_id": "SYNTHETIC-clock",
                    "visual_clock_id": "SYNTHETIC-clock", "audio_resolution_ms": 2,
                    "visual_resolution_ms": 8},
        "calibration": {"calibration_id": "SYNTHETIC-calibration", "evidence_kind": "synthetic",
                        "applies_to_capture_id": "SYNTHETIC-capture", "method": "SYNTHETIC bias",
                        "raw_artifacts": [], "audio_minus_visual_bias_ms": [8, 12]},
        "samples": [{"sample_id": "SYNTHETIC-event-0", "cycle_id": "SYNTHETIC-loop-0",
                     "source_event_id": "second-0", "audio_candidates_ms": [[70, 74]],
                     "visual_candidates_ms": [[0, 2]]}],
    }


def analyze(document):
    return ANALYSIS.analyze_document(json.dumps(document))


def escaped_surrogate_input():
    document = synthetic()
    document["record_id"] = "SYNTHETIC-语音"
    document["samples"][0]["sample_id"] = "SYNTHETIC-surrogate-placeholder"
    raw = json.dumps(document, ensure_ascii=False, indent=2)
    raw = raw.replace("SYNTHETIC-surrogate-placeholder", r"\ud800")
    return (raw.replace("\n", "\r\n") + "\r\n").encode("utf-8")


class OffsetTests(unittest.TestCase):
    def assert_surrogate_cli_report(self, run, raw):
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stderr, b"")
        report = json.loads(run.stdout.decode("utf-8"))
        self.assertEqual(report["raw_document_text"], raw.decode("utf-8"))
        self.assertEqual(report["input_sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(report["samples"][0]["sample_id"], "\ud800")
        self.assertEqual(report["samples"][0]["measured_audio_minus_visual_ms"], ["63", "79"])
        self.assertEqual(report["samples"][0]["corrected_audio_minus_visual_ms"], ["51", "71"])
        self.assertEqual(report["samples"][0]["numeric_target_relation"], "WITHIN_TARGET")
        self.assertEqual(report["measurement_validity"], "ANALYZED")
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["physical_acceptance"], "OPEN")
        self.assertIsNone(report["physical_target_relation"])
        self.assertIsNone(report["qualification_result"])

    def test_cli_escaped_surrogate_identifier_file(self):
        raw = escaped_surrogate_input()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SYNTHETIC-surrogate.json"
            path.write_bytes(raw)
            run = subprocess.run([sys.executable, "-B", str(HERE / "analyze.py"), str(path)],
                                 capture_output=True, timeout=10,
                                 env={**os.environ, "PYTHONIOENCODING": "utf-8:strict"})
            self.assert_surrogate_cli_report(run, raw)
            self.assertEqual(path.read_bytes(), raw)

    def test_cli_escaped_surrogate_identifier_stdin(self):
        raw = escaped_surrogate_input()
        run = subprocess.run([sys.executable, "-B", str(HERE / "analyze.py"), "-"], input=raw,
                             capture_output=True, timeout=10,
                             env={**os.environ, "PYTHONIOENCODING": "utf-8:strict"})
        self.assert_surrogate_cli_report(run, raw)

    def test_positive_interval_and_calibration_sign(self):
        # A=[70,74], V=[0,2], quantization adds +/-5: raw=[63,79].
        # Instrument bias=[8,12] must be subtracted: physical=[51,71].
        report = analyze(synthetic())
        event = report["samples"][0]
        self.assertEqual(event["measured_audio_minus_visual_ms"], ["63", "79"])
        self.assertEqual(event["corrected_audio_minus_visual_ms"], ["51", "71"])
        self.assertEqual(event["numeric_target_relation"], "WITHIN_TARGET")
        self.assertEqual(report["physical_acceptance"], "OPEN")
        self.assertIsNone(report["physical_target_relation"])

    def test_negative_offset_is_not_absolute_until_comparison(self):
        document = synthetic()
        document["samples"][0]["audio_candidates_ms"] = [[950, 952]]
        document["samples"][0]["visual_candidates_ms"] = [[1000, 1002]]
        event = analyze(document)["samples"][0]
        self.assertEqual(event["corrected_audio_minus_visual_ms"], ["-69", "-51"])

    def test_negative_instrument_bias_is_subtracted(self):
        document = synthetic()
        document["calibration"]["audio_minus_visual_bias_ms"] = [-12, -8]
        event = analyze(document)["samples"][0]
        self.assertEqual(event["corrected_audio_minus_visual_ms"], ["71", "91"])

    def test_boundary_and_outside_both_signs(self):
        for offset, expected in [(100, "WITHIN_TARGET"), (-100, "WITHIN_TARGET"),
                                 (101, "OUTSIDE_TARGET"), (-101, "OUTSIDE_TARGET")]:
            with self.subTest(offset=offset):
                document = synthetic()
                document["capture"].update(audio_resolution_ms=0, visual_resolution_ms=0)
                document["calibration"]["audio_minus_visual_bias_ms"] = [0, 0]
                document["samples"][0]["audio_candidates_ms"] = [[offset, offset]]
                document["samples"][0]["visual_candidates_ms"] = [[0, 0]]
                self.assertEqual(analyze(document)["samples"][0]["numeric_target_relation"], expected)

    def test_low_resolution_cannot_decide_boundary(self):
        document = synthetic()
        document["capture"].update(audio_resolution_ms=2, visual_resolution_ms=240)
        event = analyze(document)["samples"][0]
        self.assertEqual(event["corrected_audio_minus_visual_ms"], ["-65", "187"])
        self.assertEqual(event["numeric_target_relation"], "OVERLAPS_TARGET_BOUNDARY")

    def test_missing_and_ambiguous_events_are_retained_without_repair(self):
        for channel in ["audio", "visual"]:
            for candidates, expected in [([], f"MISSING_{channel.upper()}"),
                                         ([[0, 1], [1000, 1001]], f"AMBIGUOUS_{channel.upper()}")]:
                with self.subTest(channel=channel, expected=expected):
                    document = synthetic()
                    document["samples"][0][f"{channel}_candidates_ms"] = candidates
                    report = analyze(document)
                    self.assertEqual(report["samples"][0]["status"], expected)
                    self.assertIsNone(report["samples"][0]["corrected_audio_minus_visual_ms"])
                    self.assertEqual(report["summary"]["coverage"], "INCOMPLETE")
                    self.assertEqual(json.loads(report["raw_document_text"]), document)

    def test_clock_domains_are_not_subtracted(self):
        for clock in [None, "SYNTHETIC-other-device-clock"]:
            document = synthetic()
            document["capture"]["visual_clock_id"] = clock
            self.assertEqual(analyze(document)["samples"][0]["status"], "INCOMPARABLE_CLOCKS")

    def test_unknown_resolution_and_calibration_stay_unknown(self):
        for section, key, value, expected in [
            ("capture", "audio_resolution_ms", None, "UNKNOWN_RESOLUTION"),
            ("calibration", "audio_minus_visual_bias_ms", None, "UNCALIBRATED"),
            ("calibration", "applies_to_capture_id", "SYNTHETIC-unrelated", "UNCALIBRATED"),
        ]:
            with self.subTest(key=key):
                document = synthetic()
                document[section][key] = value
                event = analyze(document)["samples"][0]
                self.assertEqual(event["status"], expected)
                self.assertIsNone(event["corrected_audio_minus_visual_ms"])

    def test_opposite_offsets_do_not_cancel_in_summary(self):
        document = synthetic()
        document["calibration"]["audio_minus_visual_bias_ms"] = [0, 0]
        document["samples"][0]["audio_candidates_ms"] = [[150, 150]]
        document["samples"][0]["visual_candidates_ms"] = [[0, 0]]
        second = copy.deepcopy(document["samples"][0])
        second.update(sample_id="SYNTHETIC-second", source_event_id="second-1",
                      audio_candidates_ms=[[850, 850]], visual_candidates_ms=[[1000, 1000]])
        document["samples"].append(second)
        report = analyze(document)
        self.assertEqual(report["summary"]["numeric_target_relation"], "OUTSIDE_TARGET_OBSERVED")
        self.assertEqual(report["summary"]["corrected_offset_envelope_ms"], ["-155", "155"])
        self.assertEqual(report["summary"]["worst_absolute_bound_ms"], "155")

    def test_failure_remains_visible_with_missing_events(self):
        document = synthetic()
        document["samples"][0]["audio_candidates_ms"] = [[300, 301]]
        missing = copy.deepcopy(document["samples"][0])
        missing.update(sample_id="SYNTHETIC-missing", source_event_id="second-1", audio_candidates_ms=[])
        document["samples"].append(missing)
        summary = analyze(document)["summary"]
        self.assertEqual(summary["coverage"], "INCOMPLETE")
        self.assertEqual(summary["numeric_target_relation"], "OUTSIDE_TARGET_OBSERVED")

    def test_source_nominal_alignment_is_not_subtracted(self):
        document = synthetic()
        document["source"]["authored_audio_minus_visual_ms"] = 90
        self.assertEqual(analyze(document)["samples"][0]["corrected_audio_minus_visual_ms"], ["51", "71"])

    def test_all_kinds_keep_acceptance_open(self):
        for kind in ["synthetic", "software_diagnostic", "physical_capture"]:
            document = synthetic()
            document["evidence_kind"] = kind
            # Complete metadata is still operator-declared, never authenticated.
            document["source"].update(commit="a" * 40, artifact_sha256="b" * 64, fixture_sha256="c" * 64)
            artifact = {"path": "SYNTHETIC-not-a-real-file", "sha256": "d" * 64}
            document["capture"]["raw_artifacts"] = [artifact]
            document["calibration"].update(evidence_kind="physical_capture", raw_artifacts=[artifact])
            report = analyze(document)
            self.assertEqual(report["physical_acceptance"], "OPEN")
            self.assertIsNone(report["qualification_result"])
            self.assertEqual(report["artifact_verification"], "DECLARATIONS_ONLY_NOT_READ_OR_AUTHENTICATED")
            self.assertEqual(report["physical_target_relation"], "WITHIN_TARGET" if kind == "physical_capture" else None)

    def test_synthetic_calibration_cannot_support_physical_scope(self):
        document = synthetic()
        document["evidence_kind"] = "physical_capture"
        report = analyze(document)
        self.assertIsNone(report["physical_target_relation"])
        self.assertIn("calibration is not declared physical evidence", report["provenance_issues"])

    def test_repeated_cycles_need_explicit_distinct_identity(self):
        document = synthetic()
        second = copy.deepcopy(document["samples"][0])
        second["sample_id"] = "SYNTHETIC-next-loop"
        document["samples"].append(second)
        self.assertEqual(analyze(document)["measurement_validity"], "INVALID_INPUT")
        second["cycle_id"] = "SYNTHETIC-loop-1"
        self.assertFalse(analyze(document)["errors"])

    def test_bad_samples_preserve_original_input(self):
        for candidate in [[2, 1], [True, 2], ["0", 2], [0], None]:
            document = synthetic()
            document["samples"][0]["audio_candidates_ms"] = [candidate]
            report = analyze(document)
            self.assertEqual(report["measurement_validity"], "INVALID_INPUT")
            self.assertEqual(json.loads(report["raw_document_text"]), document)

    def test_malformed_json_duplicate_keys_nonfinite_and_wrong_root(self):
        for raw in ['{"broken":', '{"schema_version":1,"schema_version":1}',
                    '{"schema_version":NaN}', '{"value":1e9999999999999999999999999999}', '[]', 'null']:
            with self.subTest(raw=raw):
                report = ANALYSIS.analyze_document(raw)
                self.assertEqual(report["measurement_validity"], "INVALID_INPUT")
                self.assertEqual(report["raw_document_text"], raw)

    def test_wrong_enum_types_return_report_instead_of_crash(self):
        for field in ["execution_status", "evidence_kind"]:
            document = synthetic()
            document[field] = []
            self.assertEqual(analyze(document)["measurement_validity"], "INVALID_INPUT")

    def test_missing_kind_on_unexecuted_template_is_unknown(self):
        document = synthetic()
        document.update(execution_status="NOT_EXECUTED", samples=[])
        del document["evidence_kind"]
        report = analyze(document)
        self.assertEqual(report["measurement_validity"], "NOT_EXECUTED")
        self.assertIsNone(report["evidence_kind"])

    def test_arithmetic_overflow_is_a_preserved_error_report(self):
        document = synthetic()
        document["samples"][0]["audio_candidates_ms"] = [[123456789, 123456789]]
        raw = json.dumps(document).replace("123456789", "1e1000000")
        report = ANALYSIS.analyze_document(raw)
        self.assertEqual(report["measurement_validity"], "ARITHMETIC_ERROR")
        self.assertEqual(report["raw_document_text"], raw)
        self.assertIsNone(report["physical_target_relation"])

    def test_hash_digits_do_not_affect_numeric_precision(self):
        document = synthetic()
        document["source"]["artifact_sha256"] = "123e999999999" + "a" * 52
        self.assertEqual(analyze(document)["samples"][0]["corrected_audio_minus_visual_ms"], ["51", "71"])

    def test_decimal_boundary_does_not_round_into_target(self):
        document = synthetic()
        document["capture"].update(audio_resolution_ms=0, visual_resolution_ms=0)
        document["calibration"]["audio_minus_visual_bias_ms"] = [0, 0]
        document["samples"][0]["audio_candidates_ms"] = [[123456789, 123456789]]
        document["samples"][0]["visual_candidates_ms"] = [[0, 0]]
        raw = json.dumps(document).replace("123456789", "100.00000000000000000000000000000000000000001")
        self.assertEqual(ANALYSIS.analyze_document(raw)["samples"][0]["numeric_target_relation"], "OUTSIDE_TARGET")

    def test_preparation_template_has_no_observations(self):
        path = ROOT / "docs/qualifications/v0.1/bluetooth/measurement-template.json"
        report = ANALYSIS.analyze_document(path.read_text())
        self.assertEqual(report["measurement_validity"], "NOT_EXECUTED")
        self.assertIsNone(report["summary"])
        document = json.loads(path.read_text())
        document["samples"] = synthetic()["samples"]
        self.assertEqual(analyze(document)["measurement_validity"], "INVALID_INPUT")

    def test_empty_observation_is_incomplete(self):
        document = synthetic()
        document["samples"] = []
        report = analyze(document)
        self.assertEqual(report["summary"]["numeric_target_relation"], "INCOMPLETE")
        self.assertEqual(report["measurement_validity"], "INCOMPLETE")

    def test_cli_exact_raw_hash_stdin_and_invalid_exit(self):
        raw = json.dumps(synthetic(), indent=2).replace("\n", "\r\n") + "\r\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SYNTHETIC.json"
            path.write_bytes(raw.encode())
            run = subprocess.run([sys.executable, str(HERE / "analyze.py"), str(path)], capture_output=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            report = json.loads(run.stdout)
            self.assertEqual(report["raw_document_text"], raw)
            self.assertEqual(report["input_sha256"], hashlib.sha256(raw.encode()).hexdigest())
            self.assertEqual(path.read_bytes(), raw.encode())
        run = subprocess.run([sys.executable, str(HERE / "analyze.py"), "-"], input=b'{"bad":', capture_output=True, timeout=10)
        self.assertEqual(run.returncode, 2)
        self.assertEqual(json.loads(run.stdout)["raw_document_text"], '{"bad":')


if __name__ == "__main__":
    unittest.main()
