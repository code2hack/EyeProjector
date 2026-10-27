import unittest

from analyze import CLEANUP_STEPS, analyze, phone_delta


def key(seq, action, time, down=1000, device=3):
    return dict(type="key", seq=seq, activityId=7, deviceId=device, deviceName="ROKID,PSOC-TP-R",
                deviceNameScope="InputManager inventory; no event-time Binder lookup",
                source=0x101, keyCode=83, scanCode=204, action=action, flags=0, repeat=0,
                downTimeMs=down, eventTimeMs=time, receiptUptimeMs=time + 2, receiptElapsedNs=time * 1_000_000)


def stage(seq, name, time, **fields):
    return dict(type="stage", stage=name, seq=seq, activityId=7, receiptUptimeMs=time,
                receiptElapsedNs=time * 1_000_000, **fields)


class NativeTimingAnalysis(unittest.TestCase):
    def assert_unknown_marker_origin(self, action, count):
        self.assertEqual(count, action["markerCandidateCount"])
        self.assertEqual(count, len(action["markerCandidates"]))
        self.assertIsNone(action["markerOriginDeviceId"])
        self.assertEqual("unknown-unrecorded-original-routing", action["markerEligibility"])
        self.assertTrue(all(row["nativeEligibility"] == "unrecorded" for row in action["markerCandidates"]))
        self.assertNotIn("markerDownToDispatchMs", action)
        self.assertNotIn("markerUpToDispatchMs", action)

    @staticmethod
    def collision_rows():
        # Exact Reviewer counterexample: original routing is absent from the recorded stages.
        ordinary = key(1, 0, 1000, device=3)
        ordinary["deviceName"] = "ordinary keyboard"
        return [ordinary, key(2, 0, 1000, device=4), key(3, 1, 1022, device=4),
                stage(4, "dispatch_enter", 2030, actionId=1, actionType="tap", actionTimeMs=2022,
                      downTimeMs=1000, upTimeMs=1022, tapTiming="CONTACT_MARKER"),
                stage(5, "dispatch_result", 2033, actionId=1, outcome="submitted")]

    def assert_collision_stays_ambiguous(self, selected_device):
        # Keep this regression assertion compatible with the parent API: its no-filter case
        # should pass, whereas selecting either device must expose the old wrong-timing claim.
        result = analyze(self.collision_rows(), selected_device)
        self.assertEqual(1, len(result["actions"]))
        action = result["actions"][0]
        self.assertNotIn("markerDownToDispatchMs", action,
                         "Display selection cannot establish which device supplied an action")
        self.assertNotIn("markerUpToDispatchMs", action)
        self.assertEqual("ambiguous-or-unobserved", action["markerCorrelation"])
        self.assertEqual(8, action["actionTimeToDispatchMs"])
        self.assertEqual(3, action["dispatchEntryToOutcomeMs"])
        visible = {3, 4} if selected_device is None else {selected_device}
        self.assertEqual(visible, {row["deviceId"] for row in result["devices"]})
        expected_seqs = {1, 2, 3} if selected_device is None else ({1} if selected_device == 3 else {2, 3})
        self.assertEqual(expected_seqs, {row["seq"] for row in result["receiptLagMs"]})
        self.assertFalse(result["physicalInputPass"])

    def test_marker_due_dispatch_and_handler_are_separate(self):
        rows = [key(1, 0, 1000), key(2, 1, 1022),
                stage(3, "dispatch_enter", 2030, actionId=1, actionType="tap", actionTimeMs=2022,
                      downTimeMs=1000, upTimeMs=1022, tapTiming="CONTACT_MARKER"),
                stage(4, "dispatch_result", 2033, actionId=1, outcome="submitted")]
        result = analyze(rows, 3)
        action = result["actions"][0]
        self.assert_unknown_marker_origin(action, 1)
        self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])
        self.assertEqual(8, action["actionTimeToDispatchMs"])
        self.assertEqual(3, action["dispatchEntryToOutcomeMs"])
        self.assertEqual([2, 2], [row["lagMs"] for row in result["receiptLagMs"]])
        self.assertEqual(22, result["keyIntervals"][0]["reportedDurationMs"])
        self.assertFalse(result["physicalInputPass"])

    def test_short_marker_before_long_classification_never_becomes_held_edges(self):
        rows = [key(1, 0, 1000), key(2, 1, 1022), stage(3, "ai_callback", 1815, aiCallbackUptimeMs=1815),
                stage(4, "dispatch_enter", 1815, actionId=1, actionType="long-classification",
                      actionTimeMs=1815, downTimeMs=1000),
                stage(5, "dispatch_result", 1815, actionId=1, outcome="classified-only")]
        result = analyze(rows, 3)
        action = result["actions"][0]
        self.assert_unknown_marker_origin(action, 1)
        self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])
        self.assertEqual(0, action["actionTimeToDispatchMs"])
        self.assertEqual(0, action["dispatchEntryToOutcomeMs"])
        self.assertEqual(1815, result["aiCallbacks"][0]["aiCallbackUptimeMs"])
        self.assertEqual(22, result["keyIntervals"][0]["reportedDurationMs"])
        self.assertEqual([], result["motionPhases"])
        self.assertFalse(result["physicalHoldReleasePass"])

    def test_cancellation_prevents_analyzer_claiming_old_origin_matches_new_context(self):
        rows = [key(1, 0, 1000), stage(2, "cancel", 1100),
                stage(3, "dispatch_enter", 2022, actionId=1, actionType="tap", actionTimeMs=2022, downTimeMs=1000)]
        action = analyze(rows, 3)["actions"][0]
        self.assert_unknown_marker_origin(action, 1)
        self.assertEqual("canceled-between-origin-and-dispatch", action["markerCorrelation"])
        self.assertEqual("missing-terminal-observation", action["outcome"])

    def test_hidden_device_candidate_remains_unknown_and_other_activity_does_not_match(self):
        raw = key(1, 0, 1000, device=4)
        entry = stage(2, "dispatch_enter", 2022, actionId=1, actionType="tap", actionTimeMs=2022, downTimeMs=1000)
        result = analyze([raw, entry], 3)
        action = result["actions"][0]
        self.assert_unknown_marker_origin(action, 1)
        self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])
        self.assertEqual(4, action["markerCandidates"][0]["deviceId"])
        self.assertEqual([], result["devices"])
        self.assertEqual([], result["receiptLagMs"])
        raw["deviceId"], raw["activityId"] = 3, 8
        action = analyze([raw, entry], 3)["actions"][0]
        self.assert_unknown_marker_origin(action, 0)
        self.assertEqual("ambiguous-or-unobserved", action["markerCorrelation"])

    def test_collision_without_selection_keeps_ambiguous_origin_without_marker_timing(self):
        self.assert_collision_stays_ambiguous(None)

    def test_selecting_ordinary_keyboard_cannot_erase_competing_rokid_origin(self):
        self.assert_collision_stays_ambiguous(3)

    def test_selecting_rokid_cannot_erase_competing_ordinary_keyboard_origin(self):
        self.assert_collision_stays_ambiguous(4)

    def test_all_candidate_metadata_and_unknown_eligibility_survive_display_selection(self):
        rows = self.collision_rows()
        fields = ("seq", "activityId", "deviceId", "source", "keyCode", "downTimeMs",
                  "eventTimeMs", "deviceName", "deviceNameScope")
        expected = [{**{field: row[field] for field in fields}, "nativeEligibility": "unrecorded"}
                    for row in rows[:2]]
        for selection in [None, 3, 4, 99]:
            with self.subTest(selection=selection):
                result = analyze(rows, selection)
                self.assertEqual(1, len(result["actions"]))
                action = result["actions"][0]
                self.assert_unknown_marker_origin(action, 2)
                self.assertEqual(expected, action["markerCandidates"])
                self.assertEqual("ambiguous-or-unobserved", action["markerCorrelation"])
                visible = {3, 4} if selection is None else ({selection} if selection in [3, 4] else set())
                self.assertEqual(visible, {row["deviceId"] for row in result["devices"]})
                self.assertEqual(visible, {row["deviceId"] for row in result["receiptLagMs"]})
                self.assertEqual(1 if selection in [None, 4] else 0, len(result["keyIntervals"]))
                self.assertEqual(bool(visible), result["eventsObserved"])

    def test_unique_inventory_name_and_source_never_establish_original_native_routing(self):
        for name, source in [("ordinary keyboard", 0x101), ("ROKID,PSOC-TP-R", 0),
                             ("ROKID,PSOC-TP-R", 0x101)]:
            for selection in [None, 3, 4]:
                with self.subTest(name=name, source=source, selection=selection):
                    raw = key(1, 0, 1000)
                    raw.update(deviceName=name, source=source)
                    rows = [raw, stage(2, "dispatch_enter", 2030, actionId=1, actionType="tap",
                                      actionTimeMs=2022, downTimeMs=1000, upTimeMs=1022)]
                    action = analyze(rows, selection)["actions"][0]
                    self.assert_unknown_marker_origin(action, 1)
                    self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])
                    candidate = action["markerCandidates"][0]
                    self.assertEqual((3, name, source),
                                     (candidate["deviceId"], candidate["deviceName"], candidate["source"]))

    def test_unrelated_raw_reports_do_not_become_matching_marker_candidates(self):
        other_key = key(1, 0, 1000); other_key["keyCode"] = 66
        other_time = key(2, 0, 999, down=999)
        other_activity = key(3, 0, 1000); other_activity["activityId"] = 8
        repeat = key(4, 0, 1000); repeat["repeat"] = 1
        rows = [other_key, other_time, other_activity, repeat,
                key(5, 0, 1000, device=4), key(6, 1, 1022, device=4),
                stage(7, "dispatch_enter", 2030, actionId=1, actionType="tap", actionTimeMs=2022,
                      downTimeMs=1000, upTimeMs=1022),
                stage(8, "dispatch_result", 2033, actionId=1, outcome="submitted"),
                key(9, 0, 1000)] # A later observed report cannot originate the earlier dispatch.
        for selection in [None, 3, 4, 99]:
            with self.subTest(selection=selection):
                action = analyze(rows, selection)["actions"][0]
                self.assert_unknown_marker_origin(action, 1)
                self.assertEqual(5, action["markerCandidates"][0]["seq"])
                self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])

    def test_full_trace_cancellation_remains_explicit_for_both_colliding_candidates(self):
        rows = self.collision_rows()
        rows[-2]["seq"], rows[-1]["seq"] = 5, 6
        rows.insert(3, stage(4, "cancel", 1100))
        for selection in [None, 3, 4]:
            with self.subTest(selection=selection):
                action = analyze(rows, selection)["actions"][0]
                self.assert_unknown_marker_origin(action, 2)
                self.assertEqual("canceled-between-origin-and-dispatch", action["markerCorrelation"])
                self.assertEqual(8, action["actionTimeToDispatchMs"])

    def test_other_activity_cancellation_cannot_cancel_a_hidden_candidate(self):
        cancel = stage(2, "cancel", 1100); cancel["activityId"] = 8
        rows = [key(1, 0, 1000, device=4), cancel,
                stage(3, "dispatch_enter", 2030, actionId=1, actionType="tap", actionTimeMs=2022,
                      downTimeMs=1000, upTimeMs=1022)]
        action = analyze(rows, 3)["actions"][0]
        self.assert_unknown_marker_origin(action, 1)
        self.assertEqual("one-matching-delivered-marker-origin-unknown", action["markerCorrelation"])

    def test_control_samples_remain_aggregate_and_reset_on_session_change(self):
        rows = [dict(type="sample", controls=dict(startedMonoNs=1, controlsAccepted=2, controlsWritten=1)),
                dict(type="sample", controls=dict(startedMonoNs=1, controlsAccepted=4, controlsWritten=3))]
        result = analyze(rows)["controlStage"]
        self.assertEqual(2, result["deltas"]["controlsWritten"])
        self.assertIsNone(result["perEventWriteLatencyMs"])
        rows[-1]["controls"]["startedMonoNs"] = 99
        self.assertIsNone(analyze(rows)["controlStage"]["deltas"])

    def test_phone_clock_and_whitelisted_counters_are_never_mixed_with_glasses(self):
        before = dict(displayId=21, taps=0, tapDownTimesMs=[], tapUpTimesMs=[], text="not exported")
        after = dict(displayId=21, taps=2, tapDownTimesMs=[900000, 900170], tapUpTimesMs=[900022, 900192], text="not exported")
        result = analyze([key(1, 0, 1000)], 3, before, after)
        self.assertEqual([170], result["phoneFixture"]["tapDownTimesMs"]["intervalsMs"])
        self.assertNotIn("text", result["phoneFixture"]["after"])
        self.assertIsNone(result["physicalOnsetToVisibleLatencyMs"])

    def test_target_display_change_and_counter_reset_are_explicit(self):
        self.assertFalse(phone_delta(dict(displayId=21), dict(displayId=22))["sameDisplay"])
        result = phone_delta(dict(displayId=21, tapDownTimesMs=[10]), dict(displayId=21, tapDownTimesMs=[20]))
        self.assertEqual("reset-or-different-fixture", result["tapDownTimesMs"]["state"])

    def test_empty_static_case_can_close_without_becoming_physical_pass(self):
        rows = [dict(type="capture_boundary", seq=1, case="none", state="installed"),
                dict(type="capture_boundary", seq=2, case="none", state="detached")]
        report = self.complete_report(2)
        result = analyze(rows, capture_report=report)
        self.assertTrue(result["captureComplete"])
        self.assertFalse(result["eventsObserved"])
        self.assertFalse(result["physicalInputPass"])
        self.assertFalse(analyze([dict(type="capture_boundary", state="installed")])["captureComplete"])
        self.assertFalse(analyze([dict(type="capture_boundary", state="detached", complete=False)])["captureComplete"])

    @staticmethod
    def complete_report(count):
        return dict(case="none", runnerSucceeded=True, windowStarted=True, windowCompleted=True, requestedWindowMs=1000,
                    actualMeasurementMs=1000, measurementStartElapsedMs=100, measurementEndElapsedMs=1100,
                    sinkInstalled=True, exactSinkDetached=True, exportComplete=True, cleanupComplete=True,
                    inventoryThreadStopped=True, cleanupOutcomes={key: "SUCCESS" for key in CLEANUP_STEPS},
                    captureComplete=True, observerFailures=0, exportFailures=0, rowsRecorded=count, rowsWritten=count)

    def test_unavailable_aggregate_field_is_not_interpreted_as_zero(self):
        rows = [dict(type="sample", controls=dict(startedMonoNs=1, controlsWritten=None)),
                dict(type="sample", controls=dict(startedMonoNs=1, controlsWritten=3))]
        self.assertNotIn("controlsWritten", analyze(rows)["controlStage"]["deltas"])

    def test_missing_or_invalid_display_identity_is_unknown_without_deltas(self):
        for identity in [None, -1, "21", True]:
            before, after = dict(taps=0, displayId=identity), dict(taps=1, displayId=identity)
            result = phone_delta(before, after)
            self.assertIsNone(result["sameDisplay"])
            self.assertNotIn("counterDeltas", result)

    def test_missing_session_identity_is_unknown_without_deltas(self):
        rows = [dict(type="sample", controls=dict(controlsAccepted=0)),
                dict(type="sample", controls=dict(controlsAccepted=1))]
        result = analyze(rows)["controlStage"]
        self.assertIsNone(result["sameReceiverSession"])
        self.assertIsNone(result["deltas"])

    def test_detached_true_without_window_and_export_evidence_is_not_complete(self):
        rows = [dict(type="capture_boundary", state="installed"),
                dict(type="capture_boundary", state="detached", complete=True)]
        self.assertFalse(analyze(rows)["captureComplete"])

    def test_fixture_reset_does_not_keep_continuous_counter_deltas(self):
        before = dict(displayId=21, taps=3, tapDownTimesMs=[10, 20, 30])
        after = dict(displayId=21, taps=1, tapDownTimesMs=[40])
        result = phone_delta(before, after)
        self.assertNotIn("counterDeltas", result)

    def test_cleanup_success_cannot_cover_short_window_loss_or_export_failure(self):
        rows = [dict(type="capture_boundary", seq=1, case="none", state="installed"),
                dict(type="capture_boundary", seq=2, case="none", state="detached")]
        for key, value in [("windowCompleted", False), ("actualMeasurementMs", 100),
                           ("observerFailures", 1), ("exportFailures", 1), ("rowsWritten", 1),
                           ("sinkInstalled", False), ("cleanupComplete", False), ("primaryFailureType", "IOException")]:
            report = self.complete_report(2); report[key] = value
            self.assertFalse(analyze(rows, capture_report=report)["captureComplete"], key)
        self.assertFalse(analyze(rows[:1], capture_report=self.complete_report(2))["captureComplete"])
        wrapped = dict(passed=False, nativeObservation=self.complete_report(2))
        self.assertFalse(analyze(rows, capture_report=wrapped)["captureComplete"])
        report = self.complete_report(2); report["cleanupOutcomes"]["inventoryJoin"] = "FAILED:InterruptedException"
        self.assertFalse(analyze(rows, capture_report=report)["captureComplete"])

    def test_missing_middle_or_changed_session_is_not_one_continuous_interval(self):
        rows = [dict(type="sample", controls=dict(startedMonoNs=1, controlsWritten=0)),
                dict(type="sample", controls=dict(controlsWritten=1)),
                dict(type="sample", controls=dict(startedMonoNs=1, controlsWritten=2))]
        self.assertIsNone(analyze(rows)["controlStage"]["sameReceiverSession"])
        rows[1]["controls"]["startedMonoNs"] = 2
        self.assertFalse(analyze(rows)["controlStage"]["sameReceiverSession"])
        self.assertIsNone(analyze(rows)["controlStage"]["deltas"])

    def test_signed_session_nanotime_is_an_identity_not_cross_clock_time(self):
        rows = [dict(type="sample", controls=dict(startedMonoNs=-100, controlsWritten=1)),
                dict(type="sample", controls=dict(startedMonoNs=-100, controlsWritten=2))]
        result = analyze(rows)["controlStage"]
        self.assertTrue(result["sameReceiverSession"])
        self.assertEqual(1, result["deltas"]["controlsWritten"])
        self.assertIsNone(result["perEventWriteLatencyMs"])


if __name__ == "__main__":
    unittest.main()
