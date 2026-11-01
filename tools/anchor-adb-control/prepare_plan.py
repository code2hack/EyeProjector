#!/usr/bin/env python3
"""Prepare a test-plan file from supplied observations only; never calls ADB or a product hook."""
import argparse
import hashlib
import json
from pathlib import Path


def make_plan(run_id, receiver, fixture, phone):
    """phone is host-forwarded observed metadata, NOT a newly invented production command/API."""
    plan = {"runId": run_id, "commands": [{"id": "initial-observe", "op": "observe", "before": {"streaming": True}}],
            "unavailable": [], "physicalPass": False}

    def unavailable(scope, reason):
        plan["unavailable"].append({"scope": scope, "reason": reason,
                                    "sourceWitness": phone.get("sourceWitness", "supplied-observation")})

    if not phone.get("registrationReady", False):
        unavailable("anchor_registration", "production_accessibility_provider_not_observed_ready")
    if not phone.get("fixtureAnchorStateRestorable", False):
        unavailable("anchor_registration_restore", "controlled_package_prior_anchor_state_not_preserved")
    if fixture.get("runId") != run_id:
        unavailable("fixture", "different_original_singleTask_run"); return plan
    if not fixture.get("edgeTraceFile"):
        unavailable("edge_history", "fixture_trace_not_enabled_at_creation")
    width, height = receiver.get("geometryWidth"), receiver.get("geometryHeight")
    if not (receiver.get("streaming") and width and height and phone.get("identityFrameCoordinates") is True
            and phone.get("displayId") is not None and fixture.get("displayId") == phone.get("displayId")
            and phone.get("width") == width and phone.get("height") == height and phone.get("sourceWitness")):
        unavailable("target_mapping", "actual_display_geometry_or_frame_mapping_unavailable"); return plan

    def target(resource):
        controls = [c for c in fixture.get("controls", []) if c.get("viewIdResourceName", "").endswith(":id/" + resource)]
        if len(controls) != 1:
            return None
        c = controls[0]
        if not (c.get("shown") and c.get("attached") and c.get("laidOut") and c.get("displayId") == phone["displayId"]):
            return None
        left, top, right, bottom = c["layoutBoundsOnScreen"]
        x, y = (left + right) / 2, (top + bottom) / 2
        if not (0 <= x < width and 0 <= y < height and right > left and bottom > top):
            return None
        return {"frameX": x, "frameY": y, "geometryWidth": width, "geometryHeight": height,
                "phoneDisplayId": phone["displayId"], "sourceWitness": phone["sourceWitness"]}

    click, hold = target("ui_target_click"), target("ui_target_hold")
    if click is None:
        unavailable("ordinary_effect", "current_click_control_geometry_unavailable"); return plan
    source = {"sourceSessionIdentity": receiver["sessionIdentity"]} if receiver.get("sessionIdentity") is not None else {}
    plan["commands"].append({"id": "ordinary-click", "op": "surface_tap", "target": click,
                             "before": {"streaming": True, "focused": True}, **source})
    if not phone.get("registrationReady", False) or not phone.get("fixtureAnchorStateRestorable", False) or hold is None:
        if hold is None: unavailable("anchor_registration", "current_hold_control_geometry_unavailable")
        return plan
    plan["commands"] += [
        {"id": "aim-hold", "op": "aim", "target": hold, "after": {"aimAtTarget": True}, **source},
        {"id": "registration-long", "op": "native_broadcast",
         "action": "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
         "before": {"streaming": True, "aimAtTarget": True}, "after": {"chooserShown": True}, **source},
        {"id": "choose-explicit-hold", "op": "own_view_click", "view": "chooser_hold",
         "before": {"chooserShown": True}, "after": {"chooserShown": False}, **source},
    ]
    # A hidden chooser also follows UNAVAILABLE/CANCELED. It is not a saved-Hold acknowledgment.
    # A later automatic host observation may append the actual CLICK after store readback.
    readback = phone.get("fixtureAnchorReadback", {})
    if (readback.get("mode") == "HOLD" and readback.get("packageName") == "com.code2hack.eyeprojector.hostprobe"
            and readback.get("sourceWitness")):
        plan["commands"].append({"id": "arm-registered-hold", "op": "native_broadcast",
                                 "action": "com.android.action.ACTION_SPRITE_BUTTON_CLICK",
                                 "before": {"streaming": True, "pointerAvailable": True}, **source})
    plan["continuation"] = "Actual Phone store readback of controlled fixture HOLD permits appending CLICK; actual original Phone/fixture DOWN permits later Short/non-short, then original UP/CANCEL permits fresh positive. Own chooser disappearance/submission/Glasses feedback is not those witnesses."
    return plan


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-id", required=True)
    p.add_argument("--receiver-observation", type=Path, required=True)
    p.add_argument("--fixture-counter", type=Path, required=True)
    p.add_argument("--phone-observation", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    paths = [a.receiver_observation, a.fixture_counter, a.phone_observation]
    receiver, fixture, phone = [json.loads(f.read_text()) for f in paths]
    result = make_plan(a.run_id, receiver, fixture, phone)
    result["inputWitnesses"] = [{"path": str(f), "sha256": hashlib.sha256(f.read_bytes()).hexdigest()} for f in paths]
    a.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
