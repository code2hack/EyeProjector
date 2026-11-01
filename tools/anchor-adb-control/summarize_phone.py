#!/usr/bin/env python3
"""Normalize original Phone companion metadata; no Shell/app ACK or cross-clock inference."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

FIELDS = {
    "schema", "runId", "sequence", "kind", "receiptUptimeMs", "receiptElapsedNs", "pid", "uid",
    "commandId", "operation", "result", "reason", "errorType", "scope", "injectionKind", "status",
    "setup", "backend", "detail", "source", "sourceBefore", "ownerEpoch", "projectionToken",
    "uiAccessConnected", "registrationAvailability", "stagedHostIdentity", "originalOwnedSource", "ownedSource",
    "activityIdentity", "startFound", "stopFound", "requestId", "generation", "displayId", "launchStatus",
    "callbackUptimeMs", "callbackElapsedNs", "anchor", "packageName", "instanceId", "revision", "present", "mode",
    "backup", "writesAnchor", "attribution", "sourceWitness", "statusOrigin", "statusSha256", "managedState",
    "traceClosing", "traceTerminated", "originalObligations", "startsOrStopsSession", "previousPresent", "runTag",
    "commandsClaimed", "anchorBackupRetained", "originalRetirementVerified", "cleanupNormalStopSubmitted",
    "activityFinishSubmitted", "callbackKind", "physicalPass", "targetAppAcknowledgment", "inbox", "outbox", "seconds", "traceContract",
    "hostControls", "focused", "finishing", "destroyed", "start", "stop", "viewIdentity", "attached", "shown", "enabled",
    "statusReadStartElapsedNs", "statusReadEndElapsedNs",
    "appliedThenSuperseded", "committedRevision", "restoreContract",
    "successorObserved",
}


def clean(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if k in FIELDS}
    return None


def summarize(path):
    rows, malformed, other = [], 0, 0
    for line in path.read_text().splitlines():
        try:
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError()
            if value.get("schema") != "eyeprojector.anchor-adb-phone.v1":
                other += 1
                continue
            rows.append(clean(value))
        except (ValueError, TypeError):
            malformed += 1
    return {"schema": "eyeprojector.anchor-adb-phone-summary.v1", "input": str(path),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "malformed": malformed,
            "otherSchemaRows": other, "rows": rows, "kindRowCounts": dict(Counter(r.get("kind") for r in rows)),
            "physicalPass": False, "targetAppAcknowledgment": False, "crossDeviceLatencyComputed": False,
            "scope": "Phone app-process companion stream in original file order. Original Shell authority and controlled-app effects are separate witnesses. Encoded controlled Anchor backup remains only in retained original private files."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--events", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    a.output.write_text(json.dumps(summarize(a.events), indent=2) + "\n")


if __name__ == "__main__":
    main()
