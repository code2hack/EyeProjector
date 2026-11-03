# Issue165 — same-key discrete gestures

Canonical: https://github.com/code2hack/EyeProjector/issues/165

Manager: Manager-EyeProjector-v0.4.2

## Objective and Owner decision

The Owner confirmed that different gestures of the same physical HID key must coexist across actions, then explicitly requested: “update SPEC and create a new issue to implemnt it.” For example, short A → Back, long A → Home, double A → Mode. Assigning long/double A must leave short A assigned. This is a follow-up to completed #158/#159/#160 and v0.4.1, not a reopening of its historical checkpoint. The Owner subsequently authorized immediate implementation as patch v0.4.2. One coupled Worker and independent review; Manager owns targeted checks and publication.

Normative requirements: [SPEC.md — Phone HID](https://github.com/code2hack/EyeProjector/blob/main/SPEC.md#phone-hid-pin-learn-and-operate). The new coexistence/timing contract is marked pending implementation; historical version snapshots remain unchanged.

## Confirmed current defect

At main checkpoint 23e61432d9284e7a23f4d1fb945743ba716fff44:

- PhoneHidBindings.assign removes every entry with the same physical button, irrespective of gesture. Its decoder also rejects repeated buttons.
- PhoneHidBindings.assignment(button) returns the first button match; PhoneHidController selects that single action before runtime recognition.
- PhoneInputMerger and PhoneHidGestureRecognizer capture one expected action/gesture. Merely changing the assignment filter would still leave recognition and dispatch incorrect.

## Required behavior

1. Conflict identity is the selected device profile + physical key + gesture kind/count (count distinguishes multi-click gestures). Different gestures on the same key coexist. Moving the exact same key/gesture to another action replaces only that collision; assigning another gesture must not erase the other rows. Retain the existing one learned binding per action row.
2. For Back/Home/Lock/Mode, resolve all configured gestures for the original key as one sequence and dispatch exactly one matching action. Do not choose the first button match or fire short-prefix actions before long/multi is resolved.
3. Runtime timing is dynamic per key:
   - SINGLE without an assigned longer click pattern: act immediately on short release, including SINGLE + LONG.
   - SINGLE + double: first short UP stays pending; a second short click within the existing 500 ms inter-click window dispatches double once and suppresses single. If the window expires after one short click, dispatch single once.
   - MULTI N: act on its Nth short UP immediately when no longer assigned click count on that key remains possible. If a larger assigned count remains possible, wait only for that key's inter-click window. At expiry, dispatch the binding for the completed short-click count if one exists; otherwise no action.
   - A separate single-only key remains immediate while another key is pending. Never sleep/block the global input lane to implement this wait.
   - LONG remains >=800 ms and acts on release. Learning retains generic final quiet-period discovery and consumes input without app/mode effects.
4. Completion retires before dispatch/reentry; the next press begins a new sequence. Four rapid clicks of a double-only binding still produce two actions. Preserve current cancellation of invalid mixed click-then-hold patterns unless the Owner separately changes that grammar.
5. Preserve independent device profiles, one active device, exact original binding/route/context ownership, output ordering and cancellation. Stop, device/profile/session replacement and stale timers/UP/CANCEL cannot emit into a replacement or overwrite another profile. No per-event profile switching or simultaneous HID support.
6. Explicit Owner clarification: “Tap or Scroll keys are NOT ALLOWED TO SHARE, they MUST be exclusive.” Tap/ScrollUp/ScrollDown each exclusively own their physical key across all actions/gestures. Preserve existing move-on-conflict: learning a raw Tap/Scroll binding removes every prior assignment on that key; learning a discrete binding on a raw-owned key replaces that raw assignment. Distinct Back/Home/Lock/Mode gestures can then coexist. No extra confirmation flow. Preserve single-click learning, ordinary Tap multi-tap/hold/drag and Scroll400/100 ms repeat behavior.

This is the authenticated Owner decision, not an agent-added role exclusion.

## Implementation scope

Update binding/profile validation and codec/migration, learning conflict replacement, all-candidate key lookup, shared sequence recognition and Controller/Merger caller dispatch together. Keep existing native/Android identity namespaces, saved profile maps, selected fallback/native pin intent, recoverable legacy bytes and unrelated preferences. Version persistence/wire contracts only where actually required and assess both consumers if shared protocol changes. No new input grab, generic diagnostics framework, simultaneous-device arbitration, Anchor redesign or media/camera work.

UI must show coexisting rows accurately and keep a prior distinct gesture visible after learning another on that key. An actual exact-gesture conflict may move that binding; no silent clearing of other gestures.

## Acceptance and verification

- Short A → Back + long A → Home coexist through save/reload/profile switch/restart: short release Back1; >=800 ms release Home1 and Back0; no pre-release long action.
- Short A → Back + double A → Home: first release no premature Back, second qualifying release Home1/Back0; single after gap Back1; duplicate/late events do not repeat it. Retain existing499/500/501 ms boundary behavior.
- Short/long/double on A can coexist across Back/Home/Mode; exact-gesture reassignment changes only the collided action, other variants remain.
- Double + triple on A: second release waits for the assigned longer possibility; third release fires triple only, while quiet after two fires double only. An unbound completed count emits nothing. Double-only4rapid clicks still emit2actions.
- Single-only B acts during its UP call while A has a pending ambiguity; LONG alone and SINGLE+LONG introduce no final quiet wait.
- Actual learning → persistence → Controller/original route → Merger/recognizer composition verifies one action, original ownership, reentry/ordered output and cleanup; test dedicated Tap/hold/drag/Scroll repeat as regressions.
- Codec/profile migration preserves existing v0.4.1 mappings and namespaces/pin/fallback/unrelated settings. Different gesture variants on the same key decode successfully; true duplicate key+gesture is handled consistently. Test false/throw I/O and original-profile queued learning/reset.
- Tap/Scroll exclusivity: discrete variants are all moved off the key when a raw role is learned; learning discrete on a raw-owned key moves the raw binding, then distinct discrete variants coexist. Save/reload and UI rows agree. Dedicated Tap hold/drag and Scroll repeat remain unchanged.
- Independent review of the exact candidate, meaningful focused host tests and affected Android/JVM/Main checks; Manager-owned actual device checks where available. Clearly distinguish fixtures/injected inputs from physical HID/radio timing. Do not invent a hardware/Owner-publication gate from unmeasured cases.

Blocked by: None. Owner authorized immediate v0.4.2 implementation and resolved raw-role sharing: Tap/Scroll keys MUST remain exclusive. Start from current main with SPEC carrying this decision.

