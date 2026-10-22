# EP-07 — Change projected app layout scale during a session

**Canonical issue:** [#11](https://github.com/code2hack/EyeProjector/issues/11). **Blocked by:** [EP-03](03-bluetooth-video.md).

**SPEC:** [Per-app scale](../../../../SPEC.md#per-app-scale).

Establish and implement the smallest live scale operation that changes the Phone app's actual layout on the virtual display. Qualify the pinned backend's resize/density behavior with an owned fixture and expose the applied value to EP-08.

**Primary writes:** host display/scale operation, any necessary protocol/geometry handling, scale fixture and focused tests.

**Acceptance**

- A requested scale visibly changes the target layout while the same projection session remains usable.
- Numeric feedback describes the scale actually applied; rejected/unsupported requests retain truthful state.
- Updated geometry preserves pointer targeting and a usable launcher/IME area.
- Record the supported range, density/dimension mapping and any Activity recreation/state effects.
- Document any required backend change and its source/attribution.

**Verification:** scale/geometry tests and a reserved-device fixture demonstrating layout change and input after successive adjustments. The [research](../../../research/per-app-scale.md) establishes that the pinned resize message alone does not change DPI. A concrete platform limitation requires disposition for this core requirement.
