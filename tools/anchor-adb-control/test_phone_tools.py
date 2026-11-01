import json
from pathlib import Path
import tempfile
import unittest
from phone_plan import append_commands, prepare
from summarize_phone import summarize


class PhonePreparationChecks(unittest.TestCase):
    def test_config_and_stage_precede_start_and_hold(self):
        plan = prepare("run", "/mission/trace.jsonl", True)
        self.assertEqual([c["op"] for c in plan["commands"]],
                         ["observe", "configure_trace", "stage_host", "anchor_backup", "normal_start"])
        self.assertFalse(any(c["op"] in ("anchor_restore", "anchor_account_registration") for c in plan["commands"]))

    def test_source_preparation_never_implicitly_starts(self):
        self.assertNotIn("normal_start", [c["op"] for c in prepare("run")["commands"]])

    def test_append_identity_does_not_replay_or_change_original_run(self):
        original = prepare("run")
        command = {"id": "actual-source-1", "op": "capture_owned_source"}
        appended = append_commands(original, "run", [command, command])
        self.assertEqual(len(appended["commands"]), len(original["commands"]) + 1)
        self.assertEqual(len(original["commands"]), 3)
        with self.assertRaises(ValueError): append_commands(original, "different", [])
        with self.assertRaises(ValueError): append_commands(appended, "run", [{**command, "op": "normal_stop"}])

    def test_original_phone_order_and_clocks_without_private_anchor_payload(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "phone.jsonl"
            rows = [dict(schema="eyeprojector.anchor-adb-phone.v1", runId="run", sequence=2,
                         kind="command_result", result="SUBMITTED", receiptElapsedNs=900,
                         anchor=dict(packageName="com.code2hack.eyeprojector.hostprobe", revision=4,
                                     present=True, mode="HOLD", encoded="retained-private-only")),
                    dict(schema="eyeprojector.anchor-adb-phone.v1", runId="run", sequence=1,
                         kind="status_observed", receiptElapsedNs=700, uiAccessConnected=False,
                         hostControls=dict(focused=True, stop=dict(viewIdentity=42, shown=True, enabled=True))),
                    dict(schema=1, producer="Phone-Shell-Session", stage="HELD")]
            path.write_text("\n".join(json.dumps(r) for r in rows) + "\nmalformed\n")
            value = summarize(path)
            self.assertEqual([r["sequence"] for r in value["rows"]], [2, 1])
            self.assertEqual([r["receiptElapsedNs"] for r in value["rows"]], [900, 700])
            self.assertEqual((value["malformed"], value["otherSchemaRows"]), (1, 1))
            self.assertEqual(value["rows"][1]["hostControls"]["stop"]["viewIdentity"], 42)
            self.assertNotIn("retained-private-only", json.dumps(value))
            self.assertFalse(value["physicalPass"] or value["targetAppAcknowledgment"] or value["crossDeviceLatencyComputed"])


if __name__ == "__main__":
    unittest.main()
