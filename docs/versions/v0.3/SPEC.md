# v0.3 specification: explicit navigation and Anchor

Status: **Owner-directed requirements, authorized for publication and implementation on 2026-10-31.** The Owner's controls, Anchor behavior, always-explicit Click/Hold choice and contextual Home sequence are specified here. This specification is based on main `2085600efce27344f9849bf33b07a41f2da005b6`; the accepted [v0.2 baseline](../../../SPEC.md) continues to describe the implemented release.

The Owner subsequently requested that all 16 issues be published and this plan start. The completion rules below are the documented implementation direction. The [small-issue plan](README.md) records the authorized delivery breakdown; actual device capability and final version acceptance remain to be established.

## Purpose

Make normal-sized app controls usable without repeatedly aiming the head pointer while walking. The user aims once to register an app's Anchor, then operates it with Function. Navigation has separate Back, Home and Lock actions.

## Owner-defined controls

| Action | Native Rokid operation specified by the Owner | HID binding |
| --- | --- | --- |
| Back | Two-finger single tap | Back/Home: single press |
| Home | Two-finger long press | The same Back/Home binding: double press |
| Lock | Two-finger double tap | Dedicated Lock binding |
| Register Anchor | Long press Function while hovering a clickable item | Long press the Function binding |
| Activate Anchor | Short press Function | Short press the Function binding |

Back/Home is one assignable HID binding. Lock and Function are separate assignable bindings. Tap, Scroll down and Scroll up remain available. Both Phone-connected and Glasses-connected HID paths must implement the resulting roles.

Function no longer supplies the old single-press Back, double-press contextual Home or triple-press Lock behavior. Its long press registers an Anchor. Completed short Function presses operate the Anchor individually; double/triple press sequences do not become navigation. Duplicate firmware reports of a physical gesture must not perform additional operations.

The native gesture column is an Owner requirement and description of Rokid's original operations. It is not a claim that the existing EyeProjector input adapters can identify or intercept those gestures. Actual device verification must establish their reports and any firmware-handled behavior.

### Back, Home and Lock destinations

Back retains the current contextual behavior: Back in the projected app when projected input is active, otherwise Back in the local EyeProjector UI.

The Owner-defined Home sequence is:

| Current UI | Home destination |
| --- | --- |
| Projected app | EyeProjector app grid |
| EyeProjector app grid | Glasses control panel |
| Glasses control panel | Rokid Home |

Native two-finger long press and a double press of the HID Back/Home binding invoke this same contextual Home action. Function does not perform any step in this sequence.

**Completion rules:** app-to-grid and grid-to-control-panel retain projection. Control-panel-to-Rokid-Home reuses the existing explicit app-exit path, including cancellation of its original activation/input. Home from a settings subpage returns to the main control panel. These lifetime/subpage details preserve the current baseline.

Lock refers to the Glasses' existing screen sleep/lock operation, replacing the old triple-Function request. It retains the projection connection and Phone workspace as in the accepted sleep behavior. It is distinct from Stop, Disconnect and app exit. This draft does not claim a new PIN/keyguard security mechanism; the actual lock/sleep effect is the native Rokid behavior.

## One persistent Anchor per app

An Anchor belongs to an app identified by its package. Each app has at most one saved Anchor, including when the app has multiple activities or screens. Registering another Anchor for that app replaces the previous one after registration is completed.

The saved Anchor contains enough target identity/context to find the same control again, together with the user's chosen activation mode: **Click** or **Hold**. It persists alongside the app's other configuration, such as scale and notifications. One package-level Anchor does not mean one Anchor for every Activity.

The live accessibility node, window, display and touch contact are not the saved preference. A saved screen coordinate alone is not a persistent Anchor: layout, scroll, scale and window changes can move the control.

## Registration

1. Project an app and hover the head pointer over its clickable item.
2. Long-press Function to select that item for registration.
3. **Always let the user choose Click or Hold.** This choice is presented even if Android advertises click or press-and-hold capabilities. There is no automatic mode selection.
4. Completing the choice saves the target and mode as that app's sole Anchor.

**Completion rules:** registration selects/configures the target without activating the app control; release of that long Function press does not also activate an Anchor. Show a Glasses-local selection highlight and a simple Click / Hold chooser with Cancel. The existing Tap route selects a choice; Back cancels registration. The chooser consumes its own input rather than passing it through to the app. Cancel leaves the previous saved Anchor unchanged. Successful registration confirms the selected target and mode.

Target discovery must use the actual projected app/window on the current source display. Availability of the physical Phone's default-display UI is not sufficient. Visibility, actionable bounds and any required Android UI-access setup must be established on the managed display.

## Activation in the current Activity

A short Function press resolves the app's saved Anchor in the current Activity/window on the projection display when performing a Click or starting a new Hold. The head pointer does not have to hover over the Anchor again. When an Anchor contact is already held, the next short Function press releases that original contact without requiring a fresh target match.

### Click mode

Each completed short Function press performs one ordinary click on the resolved Anchor. App-defined effects remain the app's responsibility.

### Hold mode

The first completed short Function press sends a touch **DOWN** to the resolved Anchor and retains that output contact. The next completed short Function press sends **UP** for that same contact. Physical Function UP after the first press does not release the Anchor.

The held contact stays at the selected target's touch position. Head movement does not drag it. The second press releases the original contact and route, rather than looking up a new coordinate and manufacturing an unrelated UP.

The app still determines the effect of a held touch and its release. The feature does not convert an arbitrary control into a hold-capable control. No new automatic hold-duration timeout is proposed.

**Completion rules:** show a persistent local held-state indicator between DOWN and UP, and clear it when that original contact is released or canceled. Ordinary input and lifecycle cancellation remain separate from a successful Anchor UP; a canceled hold must not be reported as a completed app action.

## Completion rules: target availability and contact retirement

These completion rules were included in the published plan that the Owner authorized to start.

| Situation | Behavior | Reason and visible effect |
| --- | --- | --- |
| No Anchor has been saved for the current app | Show a brief instruction to hover a target and long-press Function; send no app input. | There is no registered app action to invoke. |
| The saved target is absent or cannot be matched uniquely in the current screen | Keep the preference and show Anchor unavailable; send no click or new DOWN to a different control. | A saved Anchor must not silently become another button. The user can navigate normally or register a replacement. |
| The target moves after scrolling, scaling, rotation or a layout change | Resolve its current bounds before a new activation. | A persistent Anchor follows the control's identity rather than its old pixel location. |
| Registration is canceled, fails to locate a target or does not complete | Keep the previous saved Anchor. | An incomplete configuration operation does not replace a working preference. |
| Back, Home, Lock, app/window replacement, geometry changes, ordinary touch/scroll handoff or session teardown retires a held input context | Cancel the original Anchor contact through the existing input-retirement path; do not carry it into another target/session or automatically press it again. | Preserve the existing single-contact and lifecycle ownership behavior. Cancellation is not a claim that the target app received a successful UP. |
| Function is pressed after a hold has already been canceled | Treat it as a new activation if the current Anchor is available. | No orphan release may be applied to a replacement contact. |

The hold toggle persists across two Function presses, not across the retirement of its original input context. A new projection must not replay a previous held Anchor automatically.

The exact native Home/Lock route must account for these contact-retirement semantics. If firmware performs an action before EyeProjector receives a report, its observable ordering and cleanup limits must be recorded rather than assuming app-level interception succeeded.

## Completion rules: settings and migration

The app's existing configuration menu gains an Anchor entry showing whether an Anchor is saved and its Click/Hold mode. It provides Clear Anchor. Clearing or replacing an Anchor retires any original active Anchor contact before subsequent use.

Removing an app from the chosen-app list preserves its Anchor, matching the current retention of scale and notification preferences. Clear Anchor is the explicit operation for deleting it.

Saved Tap/Scroll assignments and pinned-device state are preserved. The existing Function assignment remains assigned to Function with its new Anchor meaning. New Back/Home and Lock bindings start unassigned and are explicitly learned; the migration does not guess physical buttons or silently turn an old Function binding into a navigation binding.

Both endpoints and both HID routes must agree on the new roles and dispatch semantics. Updating only Glasses Function handling while retaining Phone Function navigation is not an assembled v0.3 result.

## Scope and technical validation

The delivery needs a Phone UI-target discovery/resolution path, per-app Anchor storage, registration UI, a held Anchor contact owner, and the revised navigation/HID routes. The existing HID accessibility service currently filters keys and disables UI-content retrieval; the required UI access is new functionality.

Android's accessibility tree can expose bounds, labels, IDs and actions, including windows by display. Apps may omit or merge semantic targets, and current nodes can become stale. The release must state the actually exercised app/screen coverage; it must not promise every visible button in every app can be identified. Whether the installed ChatGPT app exposes the required nodes on our managed display remains a device-validation item.

Click/Hold mode is always the user's explicit choice. Android's `ACTION_PRESS_AND_HOLD` and `ACTION_LONG_CLICK` are different capabilities and do not override that choice. The chosen sustained DOWN/UP behavior must be verified as actual touch ownership, not substituted with one accessibility long-click operation.

This version does not select a camera/audio/transport redesign or a full CXR SDK integration. The accepted v0.2 app layout, scale, camera, microphone and notification behavior remain the baseline for regression verification. Phone-call coexistence is not a new v0.3 feature.

References: [Android multi-display accessibility windows](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindowsOnAllDisplays()), [UI element metadata](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo), [press-and-hold action](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_PRESS_AND_HOLD).

## Required behavior and proposed verification

The Owner-defined behavior and the selected completion rules are the implementation goal. Concrete test apps/screens and physical observations are chosen for each verification window; test submission is not a product pass.

| Area | Required result |
| --- | --- |
| Native navigation | Actual two-finger single tap, double tap and long press produce the accepted Back/Lock/Home behavior without ordinary one-finger taps being reclassified or duplicate native operations. Firmware-handled limits are explicit. |
| HID navigation | Learned Back/Home single/double and dedicated Lock work through Phone and Glasses HID routes; saved unrelated bindings/pin state survive migration. |
| Contextual Home | Both native and HID Home follow projected app → EyeProjector app grid → Glasses control panel → Rokid Home. Lifetime/settings-subpage behavior is specified above. |
| Function reassignment | Short Function operates Anchor; long Function registers. Old Back/Home/triple-Lock actions no longer execute from Function. Verification also checks registration release and duplicate reports do not add an activation. |
| UI discovery | The actual projected window and its target bounds are identified on the managed source. Default-display-only evidence is insufficient. Regression includes physical-Phone-off/locked operation supported by v0.2. |
| Registration and persistence | Every registration offers Click/Hold and stores one target/mode per app across restart. Atomic replacement and Cancel retaining the previous preference are completion rules. |
| Click Anchor | After moving the head pointer away, each short Function press activates the registered control once in the current matching screen. |
| Hold Anchor | First short Function starts DOWN, physical Function release leaves it held, head movement does not drag it, and the next short Function releases the same contact with UP. |
| Planned changed/missing-target checks | Exercise updated bounds after layout/scale/scroll changes and the specified absent/ambiguous-target feedback without activating another control. |
| Planned retirement checks | Exercise the specified Back/Home/Lock and context/input/session retirement of original held state, with no inheritance/replay into a successor. Host submission and app-observed results are distinguished. |
| Planned app verification | ChatGPT is the initial candidate for UI discovery and Click mode, not an already accepted mandatory compatibility claim. Select a real hold-capable control for Hold mode. A controllable fixture verifies exact edges without substituting for the selected real app result. |
| Planned walking verification | Ask the Owner to assess activation while head movement makes precise aiming difficult. Host tests or synthetic pointer motion alone do not establish ergonomic success. |
| Planned regression selection | Check ordinary Tap/hold/drag/scroll, EyeProjector Home, settings, scale, notifications, projection sleep/wake, camera and existing audio behavior against the accepted baseline. |

## Implementation and acceptance boundary

The Owner has authorized the published specification and small-issue plan to be implemented. This does not establish actual native gesture delivery, managed-display UI visibility, target-app compatibility or final release acceptance. New policy outside the described behavior requires its own decision; demonstrated platform limits are reported with evidence.

The native event-delivery and managed-display UI-tree checks are engineering validation work, not additional product-choice questions. A demonstrated platform limitation is reported with evidence rather than silently changing the requested behavior.
