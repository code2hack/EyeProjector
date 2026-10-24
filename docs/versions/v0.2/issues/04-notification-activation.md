# V02-04 — Wake Glasses and open the notifying app for enabled notifications

**Canonical issue:** [#65](https://github.com/code2hack/EyeProjector/issues/65). **Blocked by:** [#63](https://github.com/code2hack/EyeProjector/issues/63), [#64](https://github.com/code2hack/EyeProjector/issues/64).

**Objective**

Connect real chosen-app notification arrivals to the shared activation path, waking the Glasses and opening the notifying app according to its saved EyeProjector preference.

**Scope**

Phone notification-listener registration/setup/status and new-event semantics; per-app current preference lookup; shared activation and existing chosen-app launch consumer.

**Source ownership**

Feature-local directhost notification listener/configuration and activation producer, manifest/resources/setup entry; narrow existing launcher/controller hook after dependency integration.

**Acceptance**

- A real new notification from an enabled chosen app wakes/connects the Glasses and opens that app; an already-active projection opens it without a duplicate session.
- Off disables this EyeProjector notification/activation route for that app while normal Phone notifications continue.
- Listener reconnection or duplicate/update delivery of an existing notification is distinguished from a newly received event.
- Arrival/app-removal/preference changes and concurrent HID activation retain current app/session ownership; late work cannot launch into a replacement session.
- Notification-listener disabled/enabled/connected status and ordinary permission setup are truthful; use only event/app metadata needed for activation, not unrelated private message bodies.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
