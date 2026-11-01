#!/usr/bin/env python3
"""Normalize only owned driver/fixture metadata; keep clocks/source streams separate, no ACK/pass."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

FIELDS = {
    "schema", "type", "seq", "sequence", "runId", "instance", "kind", "target", "caseId", "phase",
    "driverEvent", "reason", "operation", "injectionKind", "actionName", "view", "callback", "sourceWitness",
    "receiptUptimeMs", "receiptElapsedNs", "pid", "uid", "displayId", "activityId", "activityIdentity",
    "action", "actionMasked", "actionIndex", "downTimeMs", "eventTimeMs", "pointerCount", "pointerIds",
    "x", "y", "source", "deviceId", "flags", "edge", "clickCount", "holdDownCount", "holdUpCount",
    "holdCancelCount", "holding", "scope", "physicalPass", "beforeSource", "afterSource", "receiverIdentity",
    "taskId", "glassesDisplayId", "focused", "resumed", "streaming", "connectionRequested", "sessionIdentity",
    "sessionStartedMonoNs", "ownerIdentity", "inputGeneration", "sourceUsability", "ownerEpoch", "incarnation",
    "inputRevision", "mode", "revision", "id", "value", "geometryWidth", "geometryHeight", "rootWidth",
    "rootHeight", "pointerX", "pointerY", "pointerAvailable", "aimAtTarget", "chooserShown", "heldIndicatorShown",
    "matchedAnchorOverlayPresent", "heldIndicatorScope", "phoneDisplayId", "displayIdentityOrigin", "frameX", "frameY",
    "state", "ownDispatchReturned", "syntheticInputInjected",
}

PHONE_FIELDS = {
    "schema", "producer", "run", "ownerEpoch", "projectionToken", "sessionIdentity", "anchorOwnerIdentity",
    "observerSequence", "appendSequence", "jvmMonoNs", "wallMs", "thread", "stage", "contactRole",
    "contactIdentity", "contactId", "anchorSequence", "anchorGeneration", "controlIdentity", "capturedSequence",
    "receiverOwnerEpoch", "incarnation", "incarnationSequence", "inputRevision", "functionSource", "gestureId",
    "displayId", "displayUniqueId", "videoWidth", "videoHeight", "targetVideoX", "targetVideoY",
    "frameReceiptId", "frameChannel", "frameStamp", "frameBytes", "frameReceivedJvmMonoNs", "frameControlId",
    "frameControlSource", "frameGestureId", "frameFunctionSource", "frameFunctionKind", "frameFunctionSequence",
    "frameIncarnation", "frameInputRevision", "frameTouchContactId", "frameTouchPhase", "frameOriginalRetired",
    "frameSummaryOriginal", "traceDecodeFailure", "disposition", "receivedDuringRetirement", "canceling",
    "downSubmitted", "clearedOriginal", "failureFamily", "returnedNormally",
}


def clean(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if k in FIELDS}
    return None


def read(path, driver=False):
    rows, malformed = [], 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError()
            if driver and row.get("type") != "adb_control":
                continue
            rows.append(clean(row))
        except (ValueError, TypeError):
            malformed += 1
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "malformed": malformed, "rows": rows}


def read_phone(path):
    """Exact frozen API163 primitives. Keep file/append order; never cross-clock sort or join."""
    rows, malformed, other = [], 0, 0
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError()
            if row.get("schema") != 1 or row.get("producer") != "Phone-Shell-Session":
                other += 1
                continue
            rows.append({k: v for k, v in row.items() if k in PHONE_FIELDS
                         and (v is None or isinstance(v, (str, int, float, bool)))})
        except (ValueError, TypeError):
            malformed += 1
    # Captured original facts are grouped only by their literal same-process identity tuple.
    # CONTROL_* atControlObservation rows remain separate; they may refer to another contact.
    identities = ("run", "ownerEpoch", "projectionToken", "sessionIdentity", "anchorOwnerIdentity", "contactIdentity", "contactId")
    groups = {}
    for index, row in enumerate(rows):
        if row.get("contactRole") != "capturedOriginal" or any(row.get(k) is None for k in identities):
            continue
        key = tuple(row[k] for k in identities)
        group = groups.setdefault(key, {"identity": dict(zip(identities, key)), "rowIndices": [], "stages": []})
        group["rowIndices"].append(index)
        group["stages"].append(row.get("stage"))
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "malformed": malformed,
            "otherSchemaOrProducerRows": other, "rows": rows, "capturedOriginalGroups": list(groups.values()),
            "stageRowCounts": dict(Counter(row.get("stage") for row in rows)),
            "contractSha256": "8cff103852314e92a1d07231f19ef1cb932f2f0752edff49c6886fa5b48f3914",
            "scope": "Original Phone metadata only; append/observer order retained, no target app acknowledgment or cross-source contact/clock join"}


def summarize(glasses, fixture, phone=None):
    driver = read(glasses, driver=True)
    app = read(fixture)
    edges = Counter((r.get("target"), r.get("actionMasked")) for r in app["rows"] if r.get("kind") == "motion")
    return {"schema": "eyeprojector.anchor-adb-evidence.v1", "glasses": driver, "fixture": app,
            "phone": read_phone(phone) if phone is not None else None,
            "fixtureMotionRowCounts": [{"target": k[0], "actionMasked": k[1], "rows": v} for k, v in edges.items()],
            "physicalPass": False, "targetAppAcknowledgment": False, "crossDeviceLatencyComputed": False,
            "phoneGateAuthority": "Only supplied original Phone-Shell-Session rows; missing/disabled/unmatched trace remains inconclusive",
            "limits": "Own command/phase completion and literal app edges are separate evidence. Counters are separate snapshots; no cross-stream clock sorting, physical gesture count, CANCELING receipt or original contact join is invented."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--glasses-events", required=True, type=Path)
    p.add_argument("--fixture-edges", required=True, type=Path)
    p.add_argument("--phone-trace", type=Path, help="Optional retained original Phone API163 JSONL")
    p.add_argument("--output", required=True, type=Path)
    a = p.parse_args()
    a.output.write_text(json.dumps(summarize(a.glasses_events, a.fixture_edges, a.phone_trace), indent=2) + "\n")


if __name__ == "__main__":
    main()
