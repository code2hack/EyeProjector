import json
from pathlib import Path
import tempfile
import unittest
from prepare_plan import make_plan
from summarize import summarize, read_phone


class PreparationChecks(unittest.TestCase):
    def inputs(self):
        receiver = dict(streaming=True, geometryWidth=1000, geometryHeight=700, sessionIdentity=7)
        fixture = dict(runId="run", displayId=9, edgeTraceFile="owned.jsonl", controls=[
            dict(viewIdResourceName="fixture:id/ui_target_" + name, displayId=9, shown=True, attached=True,
                 laidOut=True, layoutBoundsOnScreen=[100, top, 300, top + 100]) for name, top in [("click", 100), ("hold", 300)]])
        phone = dict(displayId=9, width=1000, height=700, identityFrameCoordinates=True,
                     sourceWitness="actual-original-mapping", registrationReady=True, fixtureAnchorStateRestorable=True)
        return receiver, fixture, phone

    def test_missing_access_still_prepares_ordinary_effect(self):
        r, f, p = self.inputs(); p["registrationReady"] = False
        plan = make_plan("run", r, f, p)
        self.assertEqual([c["id"] for c in plan["commands"]], ["initial-observe", "ordinary-click"])
        self.assertEqual(plan["unavailable"][0]["scope"], "anchor_registration")
        self.assertFalse(plan["physicalPass"])

    def test_real_registration_sequence_and_explicit_display(self):
        r, f, p = self.inputs(); plan = make_plan("run", r, f, p)
        self.assertEqual(plan["commands"][-1]["view"], "chooser_hold")
        self.assertNotIn("arm-registered-hold", [c["id"] for c in plan["commands"]])
        self.assertEqual(plan["commands"][2]["target"]["phoneDisplayId"], 9)
        self.assertNotIn("release-short", [c["id"] for c in plan["commands"]])
        p["fixtureAnchorReadback"] = dict(mode="HOLD", packageName="com.code2hack.eyeprojector.hostprobe",
                                        sourceWitness="actual-store-readback")
        self.assertEqual(make_plan("run", r, f, p)["commands"][-1]["id"], "arm-registered-hold")
        p.pop("displayId"); missing = make_plan("run", r, f, p)
        self.assertEqual(len(missing["commands"]), 1)

    def test_existing_single_task_run_not_rewritten(self):
        r, f, p = self.inputs(); f["runId"] = "original"
        plan = make_plan("run", r, f, p)
        self.assertEqual(len(plan["commands"]), 1)
        self.assertEqual(f["runId"], "original")

    def test_no_registration_without_preserved_controlled_anchor(self):
        r, f, p = self.inputs(); p["fixtureAnchorStateRestorable"] = False
        plan = make_plan("run", r, f, p)
        self.assertEqual([c["id"] for c in plan["commands"]], ["initial-observe", "ordinary-click"])
        self.assertIn("anchor_registration_restore", [c["scope"] for c in plan["unavailable"]])

    def test_phone_original_identity_and_append_order_remain_separate(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "phone.jsonl"
            original = dict(schema=1, producer="Phone-Shell-Session", run="run", ownerEpoch="owner",
                            projectionToken="token", sessionIdentity=12, anchorOwnerIdentity=13,
                            contactRole="capturedOriginal", contactIdentity=14, contactId=15)
            rows = [{**original, "stage": "HELD", "observerSequence": 9, "appendSequence": 1, "jvmMonoNs": 200},
                    {**original, "stage": "CONTROL_DISPOSITION", "contactRole": "atControlObservation",
                     "contactIdentity": 99, "observerSequence": 8, "appendSequence": 2, "jvmMonoNs": 100,
                     "disposition": "CONSUMED", "frameReceiptId": 7, "frameControlId": 77, "privateText": "hidden"},
                    {**original, "stage": "CANCEL_FINISHED", "observerSequence": 10, "appendSequence": 3,
                     "clearedOriginal": True},
                    {**original, "sessionIdentity": 44, "stage": "HELD", "appendSequence": 1},
                    {"schema": 1, "producer": "other", "stage": "CANCEL_FINISHED"}]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\ninvalid\n")
            result = read_phone(path)
            self.assertEqual([r["appendSequence"] for r in result["rows"]], [1, 2, 3, 1])
            self.assertEqual([r["jvmMonoNs"] for r in result["rows"][:2]], [200, 100])
            self.assertEqual(result["capturedOriginalGroups"][0]["rowIndices"], [0, 2])
            self.assertEqual(result["capturedOriginalGroups"][1]["identity"]["sessionIdentity"], 44)
            self.assertEqual(result["rows"][1]["frameControlId"], 77)
            self.assertEqual((result["malformed"], result["otherSchemaOrProducerRows"]), (1, 1))
            self.assertNotIn("hidden", json.dumps(result))

    def test_original_edge_streams_no_private_fields_or_ack(self):
        with tempfile.TemporaryDirectory() as d:
            g, f = Path(d) / "g.jsonl", Path(d) / "f.jsonl"
            g.write_text(json.dumps(dict(type="adb_control", seq=8, runId="run", driverEvent="command_submitted",
                                        injectionKind="injected_native_broadcast", receiptElapsedNs=9000,
                                        beforeSource=dict(sessionIdentity=7, privateContents="hidden"))) + "\n")
            f.write_text("\n".join(json.dumps(dict(kind="motion", sequence=n, runId="run", instance="original",
                                                  target="hold", actionMasked=action, downTimeMs=100,
                                                  pointerIds=[0], receiptElapsedNs=2000 + n, privateText="hidden"))
                                    for n, action in [(1, 0), (2, 3)]) + "\n")
            result = summarize(g, f)
            self.assertEqual([r["actionMasked"] for r in result["fixture"]["rows"]], [0, 3])
            self.assertFalse(result["physicalPass"])
            self.assertFalse(result["targetAppAcknowledgment"])
            self.assertFalse(result["crossDeviceLatencyComputed"])
            self.assertNotIn("hidden", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
