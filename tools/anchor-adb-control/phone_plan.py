#!/usr/bin/env python3
"""Prepare a mission Phone inbox on the host only. This makes no ADB or product call."""
import argparse
import json
from pathlib import Path


def append_commands(plan, run_id, commands):
    if plan.get("runId") != run_id:
        raise ValueError("Different original Phone run")
    existing = {c["id"]: c for c in plan["commands"]}
    for command in commands:
        if command["id"] in existing:
            if existing[command["id"]] != command:
                raise ValueError("An existing command identity cannot change")
        else:
            existing[command["id"]] = command
    return {**plan, "commands": list(existing.values())}


def prepare(run_id, trace_path=None, start=False):
    commands = [{"id": "phone-initial-observe", "op": "observe"}]
    if trace_path:
        commands.append({"id": "phone-configure-trace", "op": "configure_trace", "path": trace_path})
    commands += [{"id": "phone-stage-host", "op": "stage_host"},
                 {"id": "phone-backup-fixture-anchor", "op": "anchor_backup"}]
    if start:
        commands.append({"id": "phone-normal-start", "op": "normal_start", "route": "own_view"})
    return {"runId": run_id, "commands": commands,
            "scope": "Phone test coordinator instructions only; source preparation grants no execution",
            "continuation": "Automated host observes actual source/availability before appending capture/registration/retirement/restoration commands. No human gate or app ACK is inferred."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-id", required=True)
    p.add_argument("--trace-path")
    p.add_argument("--normal-start", action="store_true", help="Prepare an explicit own-View Start command; does not execute it")
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    a.output.write_text(json.dumps(prepare(a.run_id, a.trace_path, a.normal_start), indent=2) + "\n")


if __name__ == "__main__":
    main()
