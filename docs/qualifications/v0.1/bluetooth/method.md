# Physical offset accounting — issue #22

Author: Worker: Worker-EyeProjector-22. Preparation basis: `b6675e0de27cb25cca05643fd619212ce4b4e3fd`. This method and its synthetic examples contain no physical observations. The existing target is 100 ms; this document adds no product timing limit, reset, silence expiry or operating restriction.

The quantity is **audio arrival minus visual arrival** at stated physical observation locations. Positive means the beep arrives later than the projected flash; negative means audio arrives earlier. The [packet](README.md) owns later workflow, installation, route and return accounting. The analyzer only calculates intervals from submitted event observations.

## Proposed acquisition and calibration

1. In a later assigned window, identify the actual Phone/Glasses, both installed artifacts, projected display, native audio route and fixture bytes. Reuse the existing `AvFixtureActivity` flash/beep clip. Its source PTS is a media timeline; the software's Phone/Receiver monotonic clocks are separate. Record their identity without treating them as synchronized with a recorder.
2. Observe optical output from the Glasses and acoustic output near the listening position with an apparatus that retains both on a known common timebase. A camera with original audio/video timing or optical/acoustic sensors on a common acquisition clock are possible methods, not available equipment claims. Identify apparatus, raw format, clock, actual frame/sample spacing, exposure/rolling shutter, gain, clipping and onset detector. A slow-motion export that omits audio, changes playback speed or loses the timing map cannot establish this offset.
3. Measure the capture apparatus' own signed audio-minus-visual timing bias in the same arrangement, with a traceable known physical timing reference. Preserve the reference's actual optical/acoustic alignment and uncertainty, raw calibration, procedure, date, settings and applicability to the run. Nominal simultaneous software triggers, media PTS, a clap assumed synchronous with a light, or a recorder specification without calibration do not establish zero bias. Separate capture clocks require a measured mapping with bounded uncertainty before expressing both events in a common clock; this helper does not invent that mapping.
4. State the microphone and optical observation locations, speaker/microphone distance and the intended listening reference. Retain acoustic propagation at the actual sensor location. If a claimed listening-position result needs a spatial correction, retain the measured sensor-location result and separately document the correction, its sign and uncertainty; this helper does not apply a hidden distance/source correction. Account for residual placement uncertainty in the event intervals or calibration, with the budget recorded. Do not count the same contribution twice.
5. Use the packet's planned three independent 12 s captures. Record actual counts/intervals and all expected pulses, including startup, missing/ambiguous events and loop discontinuities. The clip repeats every second and loops every 12 s: identify capture/repetition, clip cycle and pulse explicitly. Use surrounding chronology and source context. Never pair by nearest timestamp, drop an inconvenient pulse, infer a missing beep, or shift by a second to improve the result. If identity is unresolved, retain candidate alternatives.

Raw capture artifacts, calibration, event extraction and source/build bindings remain private. No later equipment availability, calibration, route, audibility, microphone or physical offset is asserted here.

## Input contract

Copy [measurement-template.json](measurement-template.json) privately. Every later actual field starts null. `execution_status` is `NOT_EXECUTED` until there are observations; an unexecuted record has no samples. Observed records declare `evidence_kind` as `physical_capture`, `software_diagnostic` or `synthetic`. These labels describe the submitted evidence; they do not authenticate it.

| Field | Meaning |
| --- | --- |
| `record_id` | Unique run/measurement identifier, linked to the qualification run. |
| `source.commit`, `artifact_sha256`, `fixture_sha256` | Exact tested source, fixture-owning Phone APK and clip bytes. Add `source.artifacts` for Glasses/other APK role, hash and installed binding; the run manifest records every artifact. |
| `source.source_clock_id` | Explicit source media-clock/PTS identity, including fixture identity. No cross-device clock mapping is implied. Optional decoded/source alignment evidence remains diagnostic. |
| `capture.capture_id`, `method` | Unique acquisition arrangement/settings and actual optical/acoustic extraction method. Identify clock mapping, event selection, exposure and uncertainty budget here or in referenced private evidence. |
| `capture.raw_artifacts` | List of `{ "path": "private-relative-reference", "sha256": "64 lowercase hex digits" }`; retain originals and extractor/tool hashes. The analyzer never opens these references. |
| `capture.audio_clock_id`, `visual_clock_id` | Same nonempty clock ID after any documented measured mapping. Unknown or differing clocks are not subtracted. Timestamps are milliseconds from that acquisition clock's stated origin. |
| `capture.audio_resolution_ms`, `visual_resolution_ms` | Full quantization-cell widths, not standard deviations. They are applied as half-widths on each side of the event intervals. Use actual event spacing, including dropped frames, rather than nominal frame rate. Null means unknown; zero is useful for exact synthetic arithmetic and cannot describe a physical acquisition. |
| `calibration.calibration_id`, `method`, `raw_artifacts` | Actual signed capture-channel bias calibration and total bounded uncertainty, including settings/reference evidence. Its `evidence_kind` must describe its actual origin. |
| `calibration.applies_to_capture_id` | Exact arrangement ID to which the calibration applies. Missing/unrelated calibration leaves a measured recorder difference uncorrected. |
| `calibration.audio_minus_visual_bias_ms` | `[lower, upper]` bounds on the apparatus' extra audio-minus-visual timestamp bias, including calibration uncertainty. An audio timestamp delayed by the apparatus has positive bias, which is subtracted. Null means unknown, never zero. |
| `samples` | Ordered expected event records; preserve every failure/absence. Additional notes/references are retained verbatim in the raw document. |

Each sample contains a nonempty unique `sample_id`, `cycle_id` and `source_event_id`. The `(cycle_id, source_event_id)` pair is unique within a document; include capture/repetition identity in `cycle_id`. `audio_candidates_ms` and `visual_candidates_ms` are arrays of `[lower, upper]` onset-detection intervals before the separately declared quantization contribution. Zero candidates means missing; more than one means ambiguous. The analyzer keeps that event unassessed instead of selecting a candidate. Bounds are finite JSON numbers with lower ≤ upper. Units are milliseconds throughout.

For example, this **synthetic** event has one candidate per channel:

```json
{
  "sample_id": "synthetic-loop0-pulse0",
  "cycle_id": "synthetic-capture1-loop0",
  "source_event_id": "second0",
  "audio_candidates_ms": [[70, 74]],
  "visual_candidates_ms": [[0, 2]]
}
```

Detection intervals bound threshold ambiguity, exposure, clipping or other stated onset uncertainty; they are not automatically confidence intervals. Quantization is added separately. If an observation is already bracketed by adjacent frame times `[t0, t1]`, represent its center as a point candidate and the frame spacing as `visual_resolution_ms`, rather than adding the full frame bracket and the same quantization again. If frame spacing varies, use a conservative width covering the listed events or separate measurement documents by arrangement. Include a documented asymmetric residual in the candidate interval as necessary. The helper cannot determine whether an uncertainty budget is physically complete.

## Calculation and target relation

Let audio onset detection be `[a0, a1]`, visual onset `[v0, v1]`, and full quantization widths `ra`, `rv`. Let capture bias be `[b0, b1]`.

```text
q = (ra + rv) / 2
measured recorder difference = [a0 - v1 - q, a1 - v0 + q]
calibrated physical difference = [measured.lower - b1, measured.upper - b0]
```

These are conservative bounded intervals, not a root-sum-square or estimated standard error. The raw measured difference and corrected physical interval are both retained. Decimal arithmetic preserves input decimal precision; computed numbers are output as decimal strings. Unsupported decimal arithmetic produces a structured error retaining the exact input, not a target relation.

For the synthetic event above, `ra=2`, `rv=8`, and bias `[8,12]` give measured `[63,79]` ms and corrected `[51,71]` ms. Negative bias is also subtracted. No authored source offset, software callback delay, historical forwarded-audio result or nominal zero PTS is subtracted. A separately measured source/baseline diagnostic may explain a result but must preserve the actual physical output offset and its own uncertainty.

The physical source is encoded at 30 fps with AAC audio; authoring cadence, decoded onset and nominal zero stream starts do not establish actual edge alignment. When directly observing physical flash/beep arrivals, bound their observed edges on the capture timebase. Do not blindly add an extra source frame of uncertainty already resolved by direct observation. A source-normalized diagnostic needs its own decoded/source calibration and must not replace this physical result.

For each corrected interval `[L,U]`, the helper reports:

- `WITHIN_TARGET` when the entire interval lies inside `[-100,100]` ms, including the boundary.
- `OUTSIDE_TARGET` when the entire interval is below `-100` or above `100` ms.
- `OVERLAPS_TARGET_BOUNDARY` otherwise; the measurement cannot decide the target relation.

This is an interval interpretation of the existing target, not a new jitter percentile, repeat-count gate or timing correction. The summary reports the signed envelope and largest absolute bound. Opposite signs never cancel in an average. A definite outside event stays `OUTSIDE_TARGET_OBSERVED` even if other events are missing. Otherwise missing/ambiguous/uncalibrated events produce `INCOMPLETE`; a within-target summary covers only the listed events, not events omitted from the input or unlimited runtime. Report actual repeat variation and failure history alongside it.

## Reproduce offline

From the repository root, with Python 3.10+ and no third-party packages:

```sh
timeout --foreground 30s python3 -B -m unittest discover -s tools/bluetooth-qualification -p 'test_*.py' -v
python3 -B tools/bluetooth-qualification/analyze.py tools/bluetooth-qualification/examples/synthetic-offsets.json > /tmp/issue22-synthetic-analysis.json
python3 -B tools/bluetooth-qualification/analyze.py docs/qualifications/v0.1/bluetooth/measurement-template.json > /tmp/issue22-unexecuted-analysis.json
```

The example is explicitly synthetic, including positive, negative, outside-target, missing, ambiguous and boundary-overlap cases. Unit tests also exercise low resolution, unrelated clocks/calibration, exact decimal boundaries, invalid input and provenance. The CLI reads only the supplied UTF-8 input path (or `-` for stdin), emits JSON on stdout and leaves the input untouched. Exit 0 means an analysis report was produced from structurally valid input; it also covers missing/failed/outside-target measurements. Exit 2 records unreadable/invalid input or an arithmetic error. Neither exit code is qualification acceptance.

Reports retain exact `raw_document_text` and its UTF-8 SHA256, including input order, extra fields, failure samples and newline bytes. They therefore inherit the input's privacy; save filled reports privately. A malformed JSON report still retains the raw text/hash. If bytes are unreadable/non-UTF-8, preserve the original file and command failure separately. Preserve each attempt's exact command, source/tool hash, input/output/log hashes and exit status in the private run manifest.

`measurement_validity` distinguishes `NOT_EXECUTED`, `INVALID_INPUT`, `ARITHMETIC_ERROR`, `INCOMPLETE` and `ANALYZED`. Unknown resolution/calibration/clocks, missing and ambiguous events stay visible per sample. Metadata completeness is reported separately as `provenance_issues`; raw artifact references are declarations, never read or authenticated. Only declared physical evidence with complete physical provenance receives a `physical_target_relation`, still without any acceptance grant. Synthetic/software evidence has no physical target relation. **Every report keeps `physical_acceptance` OPEN and `qualification_result` null.** A distinct Reviewer and Manager must evaluate the actual apparatus, raw evidence, complete uncertainty budget, route/workflow results and issue acceptance; this helper cannot do so.
