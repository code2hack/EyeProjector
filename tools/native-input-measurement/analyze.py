#!/usr/bin/env python3
"""Analyze supplied native-input observations without operating either device."""
import argparse
from collections import Counter
import json
from pathlib import Path

CONTROL_COUNTERS = ("controlsAccepted", "controlsWritten", "controlsCanceled", "controlsFailed",
                    "controlsRejected", "controlsPending", "controlsInFlight", "touchesSent",
                    "heldTouchFramesWritten", "scrollsSent", "navigationsSent")
PHONE_FIELDS = ("displayId", "active", "taps", "nativeDoubleTaps", "tapDownTimesMs", "tapUpTimesMs",
                "mainScrollY", "nestedFirst", "nestedTop")
CLEANUP_STEPS = ("observerDetach", "lifecycleUnregister", "inputListenerUnregister", "inventoryQuit",
                 "inventoryJoin", "terminalBoundary", "queueClose", "traceDrain", "traceFlush",
                 "traceClose", "traceStreamClose")


def valid_identity(value, minimum):
    return type(value) is int and value >= minimum


def capture_completion(rows, report):
    summary = (report or {}).get("nativeObservation", report or {})
    if not isinstance(summary, dict):
        summary = {}
    boundaries = [r for r in rows if r.get("type") == "capture_boundary"]
    installed = any(r.get("state") == "installed" for r in boundaries)
    detached = bool(boundaries and boundaries[-1].get("state") == "detached")
    checks = {"installedBoundary": installed, "detachedBoundary": detached}
    checks["runnerSucceeded"] = (report.get("passed") is True if report and "nativeObservation" in report
                                  else summary.get("runnerSucceeded") is True)
    for key in ("windowStarted", "windowCompleted", "sinkInstalled", "exactSinkDetached",
                "inventoryThreadStopped", "exportComplete", "cleanupComplete", "captureComplete"):
        checks[key] = summary.get(key) is True
    outcomes = summary.get("cleanupOutcomes", {})
    checks["independentCleanupOutcomes"] = isinstance(outcomes, dict) and all(outcomes.get(k) == "SUCCESS" for k in CLEANUP_STEPS)
    requested, actual = summary.get("requestedWindowMs"), summary.get("actualMeasurementMs")
    start, end = summary.get("measurementStartElapsedMs"), summary.get("measurementEndElapsedMs")
    checks["actualWindow"] = (valid_identity(requested, 1) and valid_identity(actual, 1) and
                               valid_identity(start, 0) and valid_identity(end, 0) and
                               actual == end - start and actual >= requested)
    checks["noFailures"] = all(type(summary.get(k)) is int and summary[k] == 0
                              for k in ("observerFailures", "exportFailures")) and summary.get("primaryFailureType") is None
    checks["rowAccounting"] = (type(summary.get("rowsRecorded")) is int and
                                summary.get("rowsRecorded") == summary.get("rowsWritten") == len(rows) and
                                {r.get("seq") for r in rows} == set(range(1, len(rows) + 1)))
    checks["sameCase"] = isinstance(summary.get("case"), str) and all(r.get("case") == summary["case"] for r in rows)
    return {"complete": all(checks.values()), "checks": checks,
            "scope": "Closed recorder summary plus exported trace; detachment is separate from observation coverage"}


def phone_delta(before, after):
    """Phone eventTime belongs to the Phone; never subtract a Glasses timestamp."""
    result = {"clock": "Phone Android event uptime; no cross-device subtraction",
              "before": {k: before[k] for k in PHONE_FIELDS if k in before},
              "after": {k: after[k] for k in PHONE_FIELDS if k in after}}
    ids = before.get("displayId"), after.get("displayId")
    same_display = ids[0] == ids[1] if all(valid_identity(x, 0) for x in ids) else None
    result["sameDisplay"] = same_display
    if not same_display:
        result["correlation"] = "display identity missing/invalid" if same_display is None else "display changed; counters are not one continuous target"
        return result
    reset = any(isinstance(before.get(k), (int, float)) and isinstance(after.get(k), (int, float)) and after[k] < before[k]
                for k in ("taps", "nativeDoubleTaps"))
    for key in ("tapDownTimesMs", "tapUpTimesMs"):
        old, new = before.get(key, []), after.get(key, [])
        if new[:len(old)] != old:
            result[key] = {"state": "reset-or-different-fixture"}
            reset = True
        else:
            added = new[len(old):]
            result[key] = {"new": added, "intervalsMs": [b - a for a, b in zip(added, added[1:])]}
    result["fixtureResetObserved"] = reset
    if not reset:
        result["counterDeltas"] = {k: after[k] - before[k] for k in ("taps", "nativeDoubleTaps", "mainScrollY")
                                   if isinstance(before.get(k), (int, float)) and isinstance(after.get(k), (int, float))}
    result["correlation"] = "reset-or-different-fixture; no continuous deltas" if reset else "per-case ordinal/count comparison only; no injection receipt timestamp"
    return result


def analyze(rows, device_id=None, phone_before=None, phone_after=None, capture_report=None):
    types = Counter(row.get("type", "missing") for row in rows)
    all_keys = [r for r in rows if r.get("type") == "key"]
    keys = [r for r in all_keys if device_id is None or r.get("deviceId") == device_id]
    motions = [r for r in rows if r.get("type") in ("touch_motion", "generic_motion") and
               (device_id is None or r.get("deviceId") == device_id)]
    stages = [r for r in rows if r.get("type") == "stage"]
    cancellations = [r for r in stages if r.get("stage") == "cancel"]
    output = {"schema": 1, "rows": len(rows), "types": dict(types), "selectedDeviceId": device_id,
              "physicalInputPass": False, "physicalHoldReleasePass": False,
              "physicalOnsetToVisibleLatencyMs": None,
              "scope": "App-delivered metadata and action stages; raw action origin and physical/visible response require separate evidence",
              "deviceSelectionScope": "Raw device rows for display only; action stages and possible origins use the complete trace",
              "syntheticInputGeneratedByAnalyzer": False}
    output["devices"] = list({(r.get("deviceId"), r.get("deviceName")): {
        "deviceId": r.get("deviceId"), "deviceName": r.get("deviceName"),
        "nameScope": r.get("deviceNameScope", "unrecorded")}
        for r in keys + motions}.values())
    output["receiptLagMs"] = [{"seq": r.get("seq"), "type": r["type"], "deviceId": r.get("deviceId"),
        "eventTimeMs": r["eventTimeMs"], "receiptUptimeMs": r["receiptUptimeMs"],
        "lagMs": r["receiptUptimeMs"] - r["eventTimeMs"]}
        for r in keys + motions if "eventTimeMs" in r and "receiptUptimeMs" in r]
    downs = {}
    pulses = []
    for r in keys:
        identity = (r.get("activityId"), r.get("deviceId"), r.get("keyCode"), r.get("downTimeMs"))
        if r.get("action") == 0 and r.get("repeat", 0) == 0:
            downs[identity] = r
        elif r.get("action") == 1 and identity in downs:
            down = downs.pop(identity)
            pulses.append({"deviceId": r.get("deviceId"), "keyCode": r.get("keyCode"),
                "downSeq": down.get("seq"), "upSeq": r.get("seq"),
                "reportedDurationMs": r["eventTimeMs"] - down["eventTimeMs"],
                "scope": "delivered key interval; a CONTACT83 pulse is not physical hold/release"})
    output["keyIntervals"] = pulses
    output["motionPhases"] = [{k: r.get(k) for k in ("seq", "type", "deviceId", "source", "action",
        "actionMasked", "actionIndex", "downTimeMs", "eventTimeMs", "receiptUptimeMs", "pointerCount", "pointerIds")} for r in motions]
    entries = {r["actionId"]: r for r in stages if r.get("stage") == "dispatch_enter" and r.get("actionId") is not None}
    terminal = {r["actionId"]: r for r in stages if r.get("stage") == "dispatch_result" and r.get("actionId") is not None}
    actions = []
    for identity, entry in entries.items():
        action = {k: entry.get(k) for k in ("actionId", "activityId", "actionType", "actionTimeMs",
                    "downTimeMs", "upTimeMs", "tapTiming", "generation", "receiptUptimeMs")}
        result = terminal.get(identity)
        action["outcome"] = result.get("outcome") if result else "missing-terminal-observation"
        action["dispatchEntryToOutcomeMs"] = result["receiptUptimeMs"] - entry["receiptUptimeMs"] if result else None
        if entry.get("actionTimeMs") is not None:
            action["actionTimeToDispatchMs"] = entry["receiptUptimeMs"] - entry["actionTimeMs"]
        marker_time = entry.get("downTimeMs")
        # A display filter cannot remove competing raw origins. The pre-filter observer does
        # not record the later routing decision, and inventory names do not establish it.
        candidates = [r for r in all_keys if marker_time is not None and
                      r.get("action") == 0 and r.get("repeat", 0) == 0 and
                      r.get("keyCode") == 83 and r.get("eventTimeMs") == marker_time and
                      r.get("activityId") == entry.get("activityId") and r.get("seq", 0) < entry.get("seq", 0)]
        crossed = any(r.get("activityId") == entry.get("activityId") and
                      candidate.get("seq", 0) < r.get("seq", 0) < entry.get("seq", 0)
                      for candidate in candidates for r in cancellations)
        action["markerCandidateCount"] = len(candidates)
        action["markerCandidates"] = [
            {**{k: r.get(k) for k in ("seq", "activityId", "deviceId", "source", "keyCode",
                                      "downTimeMs", "eventTimeMs", "deviceName", "deviceNameScope")},
             "nativeEligibility": "unrecorded"} for r in candidates]
        action["markerOriginDeviceId"] = None
        action["markerEligibility"] = "unknown-unrecorded-original-routing"
        action["markerCorrelation"] = "canceled-between-origin-and-dispatch" if crossed else (
            "one-matching-delivered-marker-origin-unknown" if len(candidates) == 1 else "ambiguous-or-unobserved")
        action["markerTimingScope"] = "Not calculated: original device and native routing eligibility were not observed"
        actions.append(action)
    output["actions"] = actions
    output["aiCallbacks"] = [{k: r.get(k) for k in ("seq", "activityId", "aiCallbackUptimeMs",
        "receiptUptimeMs", "receiptElapsedNs")} for r in stages if r.get("stage") == "ai_callback"]
    output["scheduledDueObservations"] = [{k: r.get(k) for k in ("seq", "activityId", "deadlineUptimeMs",
        "receiptUptimeMs", "receiptElapsedNs")} for r in stages if r.get("stage") == "deadline"]
    samples = [r for r in rows if r.get("type") == "sample" and r.get("controls")]
    control = {"scope": "periodic aggregate TLS-write counters, not per-event injection acknowledgments",
               "samples": len(samples), "sameReceiverSession": None, "deltas": None, "perEventWriteLatencyMs": None}
    if len(samples) >= 2:
        first, last = samples[0]["controls"], samples[-1]["controls"]
        identities = [r["controls"].get("startedMonoNs") for r in samples]
        # Session nanoTime is an opaque signed identity, not a positive elapsed-time measurement.
        known = [x for x in identities if type(x) is int and x != 0]
        control["sameReceiverSession"] = (False if len(set(known)) > 1 else
                                           True if len(known) == len(identities) else None)
        control["observedSessionIdentities"] = identities
        control["deltas"] = None
        if control["sameReceiverSession"]:
            control["deltas"] = {k: last[k] - first[k] for k in CONTROL_COUNTERS
                                 if isinstance(first.get(k), (int, float)) and isinstance(last.get(k), (int, float))}
            if any(value < 0 for key, value in control["deltas"].items() if key not in ("controlsPending", "controlsInFlight")):
                control["counterResetObserved"] = True
                control["deltas"] = None
        control["first"] = first
        control["last"] = last
    output["controlStage"] = control
    boundaries = [r for r in rows if r.get("type") == "capture_boundary"]
    output["captureBoundaries"] = boundaries
    output["captureCompletion"] = capture_completion(rows, capture_report)
    output["captureComplete"] = output["captureCompletion"]["complete"]
    output["eventsObserved"] = bool(keys or motions)
    if phone_before is not None and phone_after is not None:
        output["phoneFixture"] = phone_delta(phone_before, phone_after)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--device-id", type=int, help="Select raw device rows for display, not action origin or native eligibility")
    parser.add_argument("--capture-report", type=Path, help="Closed probe JSON; without it capture completeness is unestablished")
    parser.add_argument("--phone-before", type=Path)
    parser.add_argument("--phone-after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.trace.read_text().splitlines() if line.strip()]
    before = json.loads(args.phone_before.read_text()) if args.phone_before else None
    after = json.loads(args.phone_after.read_text()) if args.phone_after else None
    report = json.loads(args.capture_report.read_text()) if args.capture_report else None
    args.output.write_text(json.dumps(analyze(rows, args.device_id, before, after, report), indent=2) + "\n")


if __name__ == "__main__":
    main()
