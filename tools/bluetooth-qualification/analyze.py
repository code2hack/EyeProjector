#!/usr/bin/env python3
"""Offline interval accounting for issue22; never grants qualification acceptance.

Only reads the explicitly supplied JSON file (or stdin). It does not read artifact
references, contact devices, play media, or infer event pairs from timestamps.
See docs/qualifications/v0.1/bluetooth/method.md for units and sign conventions.
"""

import argparse
from collections import Counter
from decimal import Decimal, DecimalException, localcontext
import hashlib
import json
from pathlib import Path
import re
import sys


TARGET_MS = Decimal(100)
KINDS = {"synthetic", "software_diagnostic", "physical_capture"}


def number(value):
    """Accept finite JSON numbers, including Decimal values from the CLI parser."""
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise ValueError("expected a finite number")
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError("expected a finite number")
    return result


def interval(value):
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("expected [lower_ms, upper_ms]")
    low, high = map(number, value)
    if low > high:
        raise ValueError("lower bound exceeds upper bound")
    return low, high


def text_number(value):
    # Decimal strings preserve exact input decimal arithmetic in JSON reports.
    return str(value) if value else "0"


def bounds(values):
    return [text_number(x) for x in values]


def relation(values):
    low, high = values
    if low >= -TARGET_MS and high <= TARGET_MS:
        return "WITHIN_TARGET"
    if high < -TARGET_MS or low > TARGET_MS:
        return "OUTSIDE_TARGET"
    return "OVERLAPS_TARGET_BOUNDARY"


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def validate(document):
    """Structural errors differ from unknown/missing observation metadata."""
    errors = []
    if not isinstance(document, dict):
        return ["root must be an object"]
    if type(document.get("schema_version")) is not int or document["schema_version"] != 1:
        errors.append("schema_version must be 1")
    state = document.get("execution_status")
    if not isinstance(state, str) or state not in {"NOT_EXECUTED", "OBSERVED"}:
        errors.append("execution_status must be NOT_EXECUTED or OBSERVED")
    kind = document.get("evidence_kind")
    if not (isinstance(kind, str) and kind in KINDS) and not (state == "NOT_EXECUTED" and kind is None):
        errors.append("evidence_kind must identify synthetic, software_diagnostic or physical_capture")
    for name in ["source", "capture", "calibration"]:
        if not isinstance(document.get(name), dict):
            errors.append(f"{name} must be an object")
    samples = document.get("samples")
    if not isinstance(samples, list):
        errors.append("samples must be an array")
        return errors
    if state == "NOT_EXECUTED" and samples:
        errors.append("NOT_EXECUTED cannot contain observed samples")
    capture = document.get("capture", {})
    calibration = document.get("calibration", {})
    if isinstance(capture, dict):
        for name in ["audio_resolution_ms", "visual_resolution_ms"]:
            if capture.get(name) is not None:
                try:
                    if number(capture[name]) < 0:
                        raise ValueError("resolution cannot be negative")
                except ValueError as error:
                    errors.append(f"capture.{name}: {error}")
    if isinstance(calibration, dict) and calibration.get("audio_minus_visual_bias_ms") is not None:
        try:
            interval(calibration["audio_minus_visual_bias_ms"])
        except ValueError as error:
            errors.append(f"calibration.audio_minus_visual_bias_ms: {error}")
    ids = set()
    event_ids = set()
    for index, sample in enumerate(samples):
        prefix = f"samples[{index}]"
        if not isinstance(sample, dict):
            errors.append(f"{prefix} must be an object")
            continue
        sample_id = sample.get("sample_id")
        if not nonempty(sample_id):
            errors.append(f"{prefix}.sample_id must be a nonempty string")
        elif sample_id in ids:
            errors.append(f"{prefix}.sample_id duplicates an earlier sample")
        else:
            ids.add(sample_id)
        # Pairing is declared by the observer, never nearest-neighbor matching.
        cycle, event = sample.get("cycle_id"), sample.get("source_event_id")
        if not nonempty(cycle) or not nonempty(event):
            errors.append(f"{prefix} needs cycle_id and source_event_id")
        elif (cycle, event) in event_ids:
            errors.append(f"{prefix} duplicates a cycle/source event")
        else:
            event_ids.add((cycle, event))
        for channel in ["audio", "visual"]:
            candidates = sample.get(f"{channel}_candidates_ms")
            if not isinstance(candidates, list):
                errors.append(f"{prefix}.{channel}_candidates_ms must be an array")
                continue
            for candidate_index, candidate in enumerate(candidates):
                try:
                    interval(candidate)
                except ValueError as error:
                    errors.append(f"{prefix}.{channel}_candidates_ms[{candidate_index}]: {error}")
    return errors


def artifact_issues(artifacts, prefix):
    if not isinstance(artifacts, list) or not artifacts:
        return [f"{prefix}: no declared raw artifact"]
    issues = []
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            issues.append(f"{prefix}[{index}]: expected object")
        elif not nonempty(artifact.get("path")) or not re.fullmatch(r"[0-9a-f]{64}", str(artifact.get("sha256", ""))):
            issues.append(f"{prefix}[{index}]: path/SHA256 incomplete")
    return issues


def provenance_issues(document):
    """Completeness only: this tool cannot authenticate a recording or metadata."""
    issues = []
    if not nonempty(document.get("record_id")):
        issues.append("record_id incomplete")
    source, capture, calibration = (document[x] for x in ["source", "capture", "calibration"])
    for key, pattern in [("commit", r"[0-9a-f]{40}"), ("artifact_sha256", r"[0-9a-f]{64}"), ("fixture_sha256", r"[0-9a-f]{64}")]:
        if not re.fullmatch(pattern, str(source.get(key, ""))):
            issues.append(f"source.{key} incomplete")
    if not nonempty(source.get("source_clock_id")):
        issues.append("source.source_clock_id unknown")
    for key in ["capture_id", "method", "audio_clock_id", "visual_clock_id"]:
        if not nonempty(capture.get(key)):
            issues.append(f"capture.{key} incomplete")
    for key in ["audio_resolution_ms", "visual_resolution_ms"]:
        if capture.get(key) is None or number(capture[key]) <= 0:
            issues.append(f"capture.{key} must describe actual nonzero capture resolution")
    for key in ["calibration_id", "method"]:
        if not nonempty(calibration.get(key)):
            issues.append(f"calibration.{key} incomplete")
    if calibration.get("evidence_kind") != "physical_capture":
        issues.append("calibration is not declared physical evidence")
    if not nonempty(capture.get("capture_id")) or calibration.get("applies_to_capture_id") != capture.get("capture_id"):
        issues.append("calibration does not identify this capture arrangement")
    if calibration.get("audio_minus_visual_bias_ms") is None:
        issues.append("capture-system calibration bias unknown")
    issues.extend(artifact_issues(capture.get("raw_artifacts"), "capture.raw_artifacts"))
    issues.extend(artifact_issues(calibration.get("raw_artifacts"), "calibration.raw_artifacts"))
    return issues


def analyze_sample(sample, capture, calibration):
    result = {key: sample[key] for key in ["sample_id", "cycle_id", "source_event_id"]}
    result.update(status=None, measured_audio_minus_visual_ms=None,
                  corrected_audio_minus_visual_ms=None, numeric_target_relation=None)
    audio, visual = sample["audio_candidates_ms"], sample["visual_candidates_ms"]
    problems = []
    for name, candidates in [("AUDIO", audio), ("VISUAL", visual)]:
        if not candidates:
            problems.append(f"MISSING_{name}")
        elif len(candidates) > 1:
            problems.append(f"AMBIGUOUS_{name}")
    if problems:
        result["status"] = "+".join(problems)
        return result
    if not nonempty(capture.get("audio_clock_id")) or capture.get("audio_clock_id") != capture.get("visual_clock_id"):
        result["status"] = "INCOMPARABLE_CLOCKS"
        return result
    resolutions = [capture.get(x) for x in ["audio_resolution_ms", "visual_resolution_ms"]]
    if any(x is None for x in resolutions):
        result["status"] = "UNKNOWN_RESOLUTION"
        return result
    a_low, a_high = interval(audio[0])
    v_low, v_high = interval(visual[0])
    a_half, v_half = (number(x) / 2 for x in resolutions)
    measured = (a_low - a_half - v_high - v_half,
                a_high + a_half - v_low + v_half)
    result["measured_audio_minus_visual_ms"] = bounds(measured)
    bias = calibration.get("audio_minus_visual_bias_ms")
    if bias is None or calibration.get("applies_to_capture_id") != capture.get("capture_id") or not nonempty(capture.get("capture_id")):
        result["status"] = "UNCALIBRATED"
        return result
    b_low, b_high = interval(bias)
    corrected = measured[0] - b_high, measured[1] - b_low
    result.update(status="ANALYZED", corrected_audio_minus_visual_ms=bounds(corrected),
                  numeric_target_relation=relation(corrected))
    return result


def aggregate(samples):
    analyzed = [x for x in samples if x["status"] == "ANALYZED"]
    relations = [x["numeric_target_relation"] for x in analyzed]
    complete = bool(samples) and len(analyzed) == len(samples)
    if "OUTSIDE_TARGET" in relations:
        target_relation = "OUTSIDE_TARGET_OBSERVED"
    elif not complete:
        target_relation = "INCOMPLETE"
    elif "OVERLAPS_TARGET_BOUNDARY" in relations:
        target_relation = "OVERLAPS_TARGET_BOUNDARY"
    else:
        target_relation = "WITHIN_TARGET"
    envelope = None
    worst = None
    if analyzed:
        intervals = [tuple(Decimal(v) for v in x["corrected_audio_minus_visual_ms"]) for x in analyzed]
        envelope = bounds((min(x[0] for x in intervals), max(x[1] for x in intervals)))
        worst = text_number(max(abs(v) for x in intervals for v in x))
    return {"sample_count": len(samples), "analyzed_count": len(analyzed),
            "status_counts": dict(Counter(x["status"] for x in samples)),
            "coverage": "COMPLETE_FOR_LISTED_EVENTS" if complete else "INCOMPLETE",
            "numeric_target_relation": target_relation,
            "corrected_offset_envelope_ms": envelope, "worst_absolute_bound_ms": worst}


def analyze_document(raw_text):
    report = {"report_schema_version": 1, "target_ms": "100",
              "sign_convention": "audio arrival minus visual arrival; positive means audio later",
              "physical_acceptance": "OPEN", "qualification_result": None,
              "artifact_verification": "DECLARATIONS_ONLY_NOT_READ_OR_AUTHENTICATED",
              "input_sha256": hashlib.sha256(raw_text.encode("utf-8")).hexdigest(),
              "raw_document_text": raw_text, "errors": [], "samples": [], "summary": None,
              "physical_target_relation": None}
    try:
        def unique_object(pairs):
            obj = {}
            for key, value in pairs:
                if key in obj:
                    raise ValueError(f"duplicate JSON key: {key}")
                obj[key] = value
            return obj

        document = json.loads(raw_text, parse_float=Decimal,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite JSON token: {value}")),
                              object_pairs_hook=unique_object)
        report["errors"] = validate(document)
    except (ValueError, RecursionError, DecimalException, OverflowError) as error:
        report["errors"] = [str(error)]
    if report["errors"]:
        report["measurement_validity"] = "INVALID_INPUT"
        return report
    report["execution_status"] = document["execution_status"]
    report["evidence_kind"] = document.get("evidence_kind")
    if document["execution_status"] == "NOT_EXECUTED":
        report["measurement_validity"] = "NOT_EXECUTED"
        return report
    # Include the exponent span as well as significant digits so small bounds
    # are not rounded away when subtracting large common-clock timestamps.
    # Read actual numeric fields only: numbers inside artifact hashes are text.
    decimals = [TARGET_MS]
    decimals.extend(number(document["capture"][key]) for key in ["audio_resolution_ms", "visual_resolution_ms"] if document["capture"].get(key) is not None)
    if document["calibration"].get("audio_minus_visual_bias_ms") is not None:
        decimals.extend(interval(document["calibration"]["audio_minus_visual_bias_ms"]))
    for sample in document["samples"]:
        for channel in ["audio", "visual"]:
            for candidate in sample[f"{channel}_candidates_ms"]:
                decimals.extend(interval(candidate))
    precision = max(50, max(x.adjusted() for x in decimals) - min(x.as_tuple().exponent for x in decimals) + 10)
    try:
        with localcontext() as context:
            context.prec = precision
            report["samples"] = [analyze_sample(x, document["capture"], document["calibration"]) for x in document["samples"]]
            report["summary"] = aggregate(report["samples"])
            report["measurement_validity"] = "ANALYZED" if report["summary"]["coverage"] == "COMPLETE_FOR_LISTED_EVENTS" else "INCOMPLETE"
            report["provenance_issues"] = provenance_issues(document)
    except (DecimalException, OverflowError, ValueError) as error:
        report["errors"] = [f"decimal arithmetic could not represent input: {error}"]
        report["measurement_validity"] = "ARITHMETIC_ERROR"
        return report
    if document["evidence_kind"] == "physical_capture" and not report["provenance_issues"]:
        report["physical_target_relation"] = report["summary"]["numeric_target_relation"]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="UTF-8 measurement JSON path, or - for stdin")
    args = parser.parse_args()
    try:
        # Preserve exact newline bytes in the hash and raw text.
        raw_text = sys.stdin.buffer.read().decode("utf-8") if args.input == "-" else Path(args.input).read_bytes().decode("utf-8")
    except (OSError, UnicodeError) as error:
        print(json.dumps({"measurement_validity": "INPUT_UNREADABLE", "physical_acceptance": "OPEN", "qualification_result": None, "errors": [str(error)]}))
        return 2
    report = analyze_document(raw_text)
    print(json.dumps(report, indent=2, ensure_ascii=True, allow_nan=False))
    return 2 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
