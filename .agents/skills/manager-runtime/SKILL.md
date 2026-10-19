---
name: manager-runtime
description: Initialize or reconcile a project's private runtime.json and register its assigned Manager with an existing compatible manager-watchdog. Use for Manager startup, identity reconciliation, controlled handoff or retirement; preparing a repository does not assign the Manager role.
---

# Manager runtime

Use this skill after the Owner or authorized project governance actually assigns a Manager. Registration records that authority. A bootstrap implementer can inspect interfaces and test fixtures without becoming a Manager. Read the project's `AGENTS.md`, current assignment and exact runtime path first.

## Discover and qualify

1. Run `pwd -P`, `git rev-parse --show-toplevel`, and `git remote get-url origin`; retain the permanent checkout's physical root. Confirm the actual host, native session, Paseo agent, project and workspace. A role/name is separate from both native identity and Paseo transport identity. Collaboration children can inherit a parent's `PASEO_AGENT_ID`; an inherited value alone does not establish a binding.
2. Inspect the installed Paseo CLI/SDK before using it. Resolve `command -v paseo` with `readlink -f`, then locate its installed `dist/utils/client.js` exporting `getDaemonHost`, `resolveDaemonTarget` and `connectToDaemon`. The helper accepts that verified absolute module path through `--client`; it does not guess installation directories or install a daemon. `paseo inspect`, `paseo project ls --json` and `paseo workspace ls --json` provide useful corroboration, but CLI inspect alone omits the native session binding.
3. From the assigned Manager's actual session, run the read-only helper:

   ```sh
   SKILL_DIR="$PROJECT_ROOT/.agents/skills/manager-runtime"
   node "$SKILL_DIR/scripts/manager-runtime.mjs" inspect --client "$PASEO_CLIENT"
   ```

   Defaults read `PASEO_AGENT_ID` and `CODEX_THREAD_ID`. For another native runtime, pass its verified session ID with `--native-id "$NATIVE_ID"`. Before connecting, the helper resolves the SDK's selected endpoint, including inherited `PASEO_HOST` and configured defaults. It accepts local IPC, `localhost` and literal loopback TCP only, then passes that exact endpoint explicitly to the connector. SSH, relay and remote TCP selections fail before any connection or registry/config write. Endpoint errors omit potentially credential-bearing addresses. The helper then checks raw SDK native persistence, effective profile, project/workspace/root and archive/permission state against the local daemon. It rejects mismatches instead of importing or relabeling a session. `PROJECT_ROOT`, `PASEO_CLIENT` and other shell variables below are explicit verified values, not defaults supplied by this skill.
4. Discover where `manager-watchdog` actually executes. Inspect its executable, implementation, configured targets, user units and supported flags. Read [the installed contract](references/installed-contract.md) when qualifying compatibility. Verify on the execution host that the runtime file is readable and that its **local Paseo daemon** contains the exact Manager and all local participants it must observe. A matching absolute pathname or a remotely mounted file alone does not satisfy daemon identity. Report the concrete missing host access or daemon setup; retain existing registrations and schedule.

## Initialize or reconcile the private registry

Ensure root `runtime.json` is Git-ignored and untracked. Prepare private JSON inputs from the actual authorized run: `SETTINGS_JSON` contains settings and decision references; `CURRENT_RUN_JSON` contains the current run reference (or explicit JSON `null` if no run exists). Do not inherit another project's settings or invent concurrency, alarm or model decisions. `ASSIGNMENT` is a readable absolute durable assignment/run record.

```sh
node "$SKILL_DIR/scripts/manager-runtime.mjs" register \
  --client "$PASEO_CLIENT" --name "$MANAGER_NAME" \
  --assignment "$ASSIGNMENT" --status "$EXPECTED_STATUS" \
  --settings "$SETTINGS_JSON" --current-run "$CURRENT_RUN_JSON"
```

For an existing registry, settings/current run and all extension fields remain intact; omit the two initialization inputs on subsequent calls. Reconcile authorized settings/run changes separately with the same atomic writer discipline and revision increment. `status` is the **expected** `running`, `idle` or `waiting` value from the assignment, independent of observed SDK state. Explain holds in the run record. A model/profile reconciliation preserves the native identity, assignments, pending requests and attributable attempt history.

The helper preserves other participants and rejects a conflicting Manager/project or duplicate identity. It writes mode `0600`, increments revision only for a semantic change, uses a same-directory atomic rename with file/directory sync, and fails on a writer lock or externally changed file. Reconcile stale locks against the owning operation before removing one; do not poll or overwrite a live writer. Writers must cooperate on `<file>.manager-runtime.lock` or be serialized by the Manager.

## Register monitoring and verify readiness

Run this on the **verified watchdog execution host**, from the same repository root and actual Manager session. `WATCHDOG_MODULE` names the inspected compatible implementation; `WATCHDOG_CONFIG` is its existing configuration. The helper uses the implementation's own validators before changing config, preserves the shared interval and all other project targets, and atomically adds or reconciles exactly this `{paseoId, runtimeFile}` pair.

```sh
node "$SKILL_DIR/scripts/manager-runtime.mjs" monitor \
  --client "$PASEO_CLIENT" --watchdog "$WATCHDOG_MODULE" \
  --config "$WATCHDOG_CONFIG"
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --check
systemctl --user is-enabled manager-watchdog.timer
systemctl --user is-active manager-watchdog.timer
systemctl --user show manager-watchdog.service -p Result -p TimeoutStartUSec
systemctl --user list-timers manager-watchdog.timer
```

Inspect the read-only check's per-project results: process success by itself is insufficient (`manager-waiting` and a busy-manager deferral can be valid states). A oneshot service may be inactive between ticks. Record its last result, timer readiness, actual runtime path and exact bindings. Read-only `--check` never sends or proves recipient receipt.

When the target count changes, the existing implementation's service timeout also depends on target count. Render its existing units and reload them, preserving its interval:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --write-units
systemctl --user daemon-reload
systemctl --user enable --now manager-watchdog.timer
systemctl --user restart manager-watchdog.timer
```

Use these supported installation operations only for the shared watchdog being configured; do not create another scheduler. An ordinary live `--run`/service tick may send notifications, so use `--check` for qualification. A write does not prove monitoring readiness until validation, daemon binding and unit checks pass.

## Handoff or retire

First transfer run state, children, write/resource ownership, pending request correlations, evidence and deferred cleanup duties. Verify the successor's receipt and bindings; settle the outgoing controller. Preserve its exact participant record and continuation/cleanup references in a durable handoff record. For the installed validator, the live registry must have exactly one `Manager`; retain the outgoing history and queued archival outside the live registry after safe handoff.

Prepare a private JSON handoff input containing:

```json
{
  "previousManager": {"...": "exact current Manager object from runtime.json"},
  "successor": {"host": "verified-host", "runtime": "codex", "id": "verified-native-id", "paseo": "verified-paseo-id"},
  "record": "/absolute/durable/handoff-record.json"
}
```

The example is a shape only. The readable `record` must preserve the outgoing identity and verified continuity/receipt/settlement evidence; the helper validates the exact tuple, not the truth of that evidence. From the successor's real session, use `register` with `--handoff "$HANDOFF_JSON"`, then `monitor` with `--replace-id "$OUTGOING_PASEO_ID"`. This replaces only the recorded Manager and exact monitor for the same runtime file. Registry and watchdog config are separate atomic writes; serialize the handoff and reconcile their temporary mismatch with `--check` before declaring readiness.

For retirement without a successor, verify settlement, recoverable evidence and applicable workspace archival first. Preserve run/history before removing the live registration. Remove only its exact watchdog target:

```sh
node "$SKILL_DIR/scripts/manager-runtime.mjs" unmonitor \
  --config "$WATCHDOG_CONFIG" --runtime "$RUNTIME_FILE" \
  --paseo-id "$RETIRED_PASEO_ID"
```

`unmonitor` does not contact or archive an agent. It refuses a conflicting pair and the last target because the installed watchdog requires a nonempty list; retiring the final monitor needs an explicit shared-service retirement decision. Rerender units after a target-count change. Verify remaining projects with `--check`. Necessary source/resource teardown is immediate; discretionary file cleanup and archival follow the recorded run completion boundary.

## Fixture verification

```sh
node --test "$SKILL_DIR/tests/manager-runtime.test.mjs"
```

Fixtures use temporary files and fake identities: initialization, idempotency, conflict refusal, native/project/profile verification, exact handoff, multiple projects, retirement, interrupted/concurrent atomic writes and endpoint selection. Remote selections must fail before connection or mutation; supported local selections must be passed explicitly. They do not mutate live registries, send messages, start timers or establish actual Manager authority.
