# V02-03 — Add per-app Glasses notifications toggle to the Phone app menu

**Canonical issue:** [#64](https://github.com/code2hack/EyeProjector/issues/64). **Blocked by:** None.

**Objective**

Add a persistent per-chosen-app Glasses-notification setting to the Phone icon long-press menu alongside Remove and Scale.

**Scope**

Small independent Phone preference store and chosen-app menu/resources; no notification-listener, wake or transport implementation in this issue.

**Source ownership**

directhost notifications preference helper/store; apps/ChosenAppsPanel.kt menu hook/resources and affected existing Phone tests. No initial HostActivity/session/backend/Receiver/protocol write window.

**Acceptance**

- Long-press menu exposes Remove, current-context Scale and Glasses notifications On/Off with the actual saved state.
- Preference is independent per app, configurable disconnected, and survives app restart/list reload without changing chosen-app order or scale.
- Default On for chosen apps is recorded as the initial implementation default; explicit user choices persist.
- Toggle changes only EyeProjector routing preferences and leaves Phone/system notification settings and delivery intact.
- Expose a narrow current preference lookup for the dependent notification-listener issue; meaningful store/menu checks retain Remove and Scale behavior.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
