import json
import unittest

from observe import analyze


class ObservationChecks(unittest.TestCase):
    def test_real_report_identity_order_and_no_gesture_inference(self):
        app = [json.dumps(row) for row in [
            {"type": "key", "seq": 4, "deviceId": 3, "source": 257, "keyCode": 4,
             "scanCode": 158, "action": 0, "eventTimeMs": 320},
            {"type": "generic_motion", "seq": 5, "pointerCount": 2, "actionMasked": 5},
            {"type": "key", "seq": 6, "deviceId": 3, "source": 257, "keyCode": 4,
             "scanCode": 158, "action": 0, "eventTimeMs": 321},
        ]]
        result = analyze(app)
        self.assertEqual([4, 5, 6], [row["seq"] for row in result["app"]["events"]])
        self.assertEqual(2, result["app"]["events"][1]["pointerCount"])
        self.assertEqual([4, 6], result["appReportOccurrences"][0]["sequences"])
        self.assertNotIn("pointerCount", result["app"]["events"][0])
        self.assertEqual("UNKNOWN", result["firmwareActionOwnership"])
        self.assertEqual("UNESTABLISHED", result["physicalGestureClassification"])

    def test_kernel_symbolic_numeric_and_missing_pointer_count(self):
        result = analyze(kernel_lines=[
            "[  7.000001] /dev/input/event1: EV_MSC MSC_SCAN 00000094",
            "[  7.000002] /dev/input/event1: EV_KEY KEY_PROG1 DOWN",
            "[  7.000003] EV_KEY KEY_PROG1 UP",
            "[  7.000004] 0003 0039 00000002",
            "not an event / private data omitted",
        ], node="/dev/input/event2")
        events = result["kernel"]["events"]
        self.assertEqual(4, len(events))
        self.assertEqual("00000094", events[0]["value"])
        self.assertEqual("/dev/input/event1", events[1]["node"])
        self.assertEqual("/dev/input/event2", events[2]["node"])
        self.assertEqual("7.000004", events[3]["kernelTimeSeconds"])
        self.assertTrue(all(row["pointerCount"] is None for row in events))
        self.assertEqual(1, result["kernel"]["unmatchedLines"])
        self.assertNotIn("private data", json.dumps(result))

    def test_private_fields_and_broadcast_extras_are_not_exported(self):
        result = analyze([json.dumps({"type": "broadcast", "seq": 1, "actionName": "known-source-action",
                                     "ordered": True, "extras": {"text": "PRIVATE"}, "data": "PRIVATE"}),
                          json.dumps({"type": "sample", "receiverInput": {"text": "PRIVATE"}}), "[broken"])
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual(1, result["app"]["malformedLines"])
        self.assertEqual(1, result["app"]["omittedNonEventRows"])
        self.assertEqual(1, len(result["app"]["events"]))

    def test_no_cross_clock_sorting_and_no_absence_pass(self):
        result = analyze([json.dumps({"type": "interactive_sample", "seq": 2, "interactive": False,
                                     "receiptElapsedNs": 999999999999})],
                         ["[  1.000001] EV_KEY KEY_BACK DOWN"])
        self.assertEqual(2, result["app"]["events"][0]["seq"])
        self.assertEqual(1, result["kernel"]["events"][0]["seq"])
        self.assertEqual("UNKNOWN", result["firmwareActionOwnership"])
        self.assertNotIn("passed", result)


if __name__ == "__main__":
    unittest.main()
