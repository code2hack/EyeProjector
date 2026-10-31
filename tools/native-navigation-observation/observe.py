#!/usr/bin/env python3
"""Normalize supplied navigation metadata; never opens a device or classifies a gesture."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import sys


GETEVENT = re.compile(
    r"^\s*\[\s*(?P<time>\d+\.\d+)\]\s*"
    r"(?:(?P<node>/dev/input/event\d+):\s*)?"
    r"(?P<eventType>EV_[A-Z0-9_]+|[0-9a-fA-F]{4})\s+"
    r"(?P<code>[A-Z][A-Z0-9_]*|[0-9a-fA-F]{4})\s+"
    r"(?P<value>DOWN|UP|REPEAT|[0-9a-fA-F]{8})\s*$"
)
APP_FIELDS = {
    "key": ("deviceId", "deviceName", "deviceNameObservedElapsedNs", "source", "keyCode",
            "scanCode", "action", "flags", "repeat", "downTimeMs", "eventTimeMs"),
    "touch_motion": ("deviceId", "deviceName", "source", "action", "actionMasked", "actionIndex",
                     "pointerCount", "pointerIds", "flags", "downTimeMs", "eventTimeMs"),
    "generic_motion": ("deviceId", "deviceName", "source", "action", "actionMasked", "actionIndex",
                       "pointerCount", "pointerIds", "flags", "downTimeMs", "eventTimeMs"),
    "broadcast": ("actionName", "intentFlags", "ordered", "initialSticky", "resultCodeAtReceipt",
                  "observerAborted", "observerChangedResult"),
    "lifecycle": ("state",),
    "capture_boundary": ("state",),
    "interactive_sample": ("interactive", "readStartElapsedNs", "readEndElapsedNs"),
    "stage": ("stage", "actionId", "actionType", "actionTimeMs", "downTimeMs", "upTimeMs",
              "outcome", "local", "generation", "aiCallbackUptimeMs"),
}


def normalize_kernel(lines, case, node=None):
    events, unmatched = [], 0
    for line_number, line in enumerate(lines, 1):
        match = GETEVENT.fullmatch(line.rstrip("\n"))
        if not match:
            unmatched += 1
            continue
        fields = match.groupdict()
        events.append({"type": "kernel", "seq": len(events) + 1, "inputLine": line_number,
                       "case": case, "clockDomain": "getevent kernel timestamp; no app-clock alignment",
                       "kernelTimeSeconds": fields.pop("time"),
                       **fields, "node": fields["node"] or node, "pointerCount": None})
    return {"events": events, "unmatchedLines": unmatched,
            "pointerCountScope": "Not inferred from keys/partial MT slots; retain actual ABS/MSC/SYN reports"}


def normalize_app(lines):
    events, malformed, omitted = [], 0, 0
    for line_number, line in enumerate(lines, 1):
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("Expected metadata object")
        except (ValueError, TypeError):
            malformed += 1
            continue
        kind = row.get("type")
        if kind not in APP_FIELDS:
            omitted += 1
            continue
        fields = ("type", "seq", "case", "activityId", "receiptUptimeMs", "receiptElapsedNs") + APP_FIELDS[kind]
        events.append({**{key: row[key] for key in fields if key in row}, "inputLine": line_number,
                       "clockDomain": "Glasses Android uptime/elapsed; eventTimeMs is event uptime"})
    return {"events": events, "malformedLines": malformed, "omittedNonEventRows": omitted}


def summarize(events):
    identities = defaultdict(list)
    for row in events:
        kind = row["type"]
        if kind == "key":
            keys = ("deviceId", "source", "keyCode", "scanCode", "action", "repeat")
        elif kind in ("touch_motion", "generic_motion"):
            keys = ("deviceId", "source", "action", "actionMasked", "pointerCount")
        elif kind == "broadcast":
            keys = ("actionName", "ordered", "intentFlags")
        elif kind == "kernel":
            keys = ("node", "eventType", "code", "value")
        else:
            continue
        signature = {"type": kind, **{key: row.get(key) for key in keys}}
        identities[json.dumps(signature, sort_keys=True)].append(row.get("seq"))
    return [{"identity": json.loads(identity), "count": len(sequences), "sequences": sequences}
            for identity, sequences in identities.items()]


def analyze(app_lines=(), kernel_lines=(), case="unclassified", node=None):
    app = normalize_app(app_lines)
    kernel = normalize_kernel(kernel_lines, case, node)
    return {"schema": 1, "app": app, "kernel": kernel,
            "appReportOccurrences": summarize(app["events"]),
            "kernelReportOccurrences": summarize(kernel["events"]),
            "occurrenceScope": "Repeated report identities only; no deduplication or physical-operation counts",
            "firmwareActionOwnership": "UNKNOWN",
            "physicalGestureClassification": "UNESTABLISHED",
            "captureCompleteness": "Requires original closed instrumentation report and kernel command outcome",
            "orderingScope": "Preserve each input stream order; do not subtract or merge unrelated clocks",
            "broadcastAbsenceScope": "Subscribed source actions only; missing receipt is inconclusive; earlier abort may shadow ordered channels; unordered receipt does not establish interception"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-jsonl", type=Path)
    parser.add_argument("--getevent", help="Supplied getevent -lt file, or '-' for stdin")
    parser.add_argument("--case", default="unclassified")
    parser.add_argument("--node", help="Actual observed node if getevent omitted its name")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.app_jsonl and not args.getevent:
        parser.error("Supply --app-jsonl and/or --getevent")
    app = args.app_jsonl.read_bytes() if args.app_jsonl else b""
    kernel = (sys.stdin.buffer.read() if args.getevent == "-" else Path(args.getevent).read_bytes()) if args.getevent else b""
    result = analyze(app.decode().splitlines(), kernel.decode().splitlines(), args.case, args.node)
    result["inputs"] = {"appSha256": hashlib.sha256(app).hexdigest() if args.app_jsonl else None,
                        "kernelSha256": hashlib.sha256(kernel).hexdigest() if args.getevent else None}
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"appEvents": len(result["app"]["events"]),
                      "kernelEvents": len(result["kernel"]["events"]), "output": str(args.output)}))


if __name__ == "__main__":
    main()
