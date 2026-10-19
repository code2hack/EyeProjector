---
name: manager-runtime
description: Initialize a project's private runtime.json and register its assigned Manager with the existing manager-watchdog for an authorized version. Use for version start, Manager identity reconciliation, handoff and version completion; repository preparation alone does not assign a Manager or begin a version.
---

# Manager runtime

Read the project's `AGENTS.md`, authorized version assignment and the formal runtime chapter in [`RUNBOOK.md`](../../../RUNBOOK.md). That chapter owns the schema and field meanings. This skill implements setup and registration, not scheduling or product acceptance.

## Verify the actual Manager

1. Resolve `pwd -P`, the Git root and origin. Retain that permanent checkout. Verify the native session, actual host, Paseo agent, project and workspace separately; a role/name or inherited `PASEO_AGENT_ID` does not establish a native identity. A collaboration child can inherit its parent's transport binding.
2. Discover the installed Paseo CLI and its SDK `dist/utils/client.js` exporting `getDaemonHost`, `resolveDaemonTarget` and `connectToDaemon`. Do not guess installation paths. From the assigned Manager's actual session:

   ```sh
   SKILL_DIR="$PROJECT_ROOT/.agents/skills/manager-runtime"
   node "$SKILL_DIR/scripts/manager-runtime.mjs" inspect --client "$PASEO_CLIENT"
   ```

   Defaults read `PASEO_AGENT_ID` and `CODEX_THREAD_ID`; another native runtime supplies its verified `--native-id`. Before assigning the local hostname, the helper validates and pins the selected local IPC or loopback daemon endpoint. It checks native persistence, effective profile, project/workspace/root and archive/permission state. Remote endpoint selection fails before connection or mutation; errors omit endpoint credentials. The watchdog's separately configured remote observation route is independent of this local identity check.
3. Establish actual Manager assignment and the Owner-authorized version. Repository/bootstrap preparation can run read-only inspection and fixtures, but does not activate monitoring.

## Initialize or reconcile runtime.json

Root `runtime.json` stays private, Git-ignored and untracked. Prepare private `SETTINGS_JSON` and `CURRENT_VERSION_JSON` inputs from the RUNBOOK chapter and current authorization. Use explicit JSON `null` when no version exists. `ASSIGNMENT` is a readable absolute assignment/version-record path. Initialize from the current chapter; do not copy another project's runtime.

```sh
node "$SKILL_DIR/scripts/manager-runtime.mjs" register \
  --client "$PASEO_CLIENT" --name "$MANAGER_NAME" \
  --assignment "$ASSIGNMENT" --status "$EXPECTED_STATUS" \
  --settings "$SETTINGS_JSON" --current-version "$CURRENT_VERSION_JSON"
```

The helper creates the formal schema's empty operational collections and the verified Manager. It defaults `settings.guardApprovalTimeoutSeconds` to 600; the Owner's configured value takes precedence. Subsequent calls preserve settings, missions, reservations, guard requests, credential references, pending correlations and extension fields. An explicitly supplied `--current-version` updates the version; omit initialization inputs when reconciling identity/profile only. Preserve the prior version in durable history before switching versions. `status` is expected `running`, `idle` or `waiting`, distinct from actual Paseo lifecycle. Participant roles are Manager, Worker and Reviewer.

The helper refuses an older schema without altering it. Before an explicit migration, preserve the entire previous file as a private immutable snapshot, reconcile its live participants/assignments and retained history against the new chapter, then use the same atomic writer to replace it with verified schema-2 data. Record the migration and source snapshot in the version record. Avoid silently dropping older identities or operational records.

Writes use mode `0600`, a cooperative per-file writer lock, file/directory sync and same-directory atomic rename. Semantic identity reconciliation is idempotent and increments revision only when changed. An external concurrent edit fails rather than overwriting it. Inspect a stale lock's owning operation before removing it. Other writers use the same lock or serialize through the Manager.

## Owner-requested credential retention

Only when the Owner explicitly requests retention, use [`save-credential.py`](scripts/save-credential.py) through a new uniquely named window in the persistent `sudo` tmux session, creating the session if absent and leaving its initial and unrelated windows intact. Resolve the exact command and private path outside the repository first. The Owner types the value directly into that window twice with echo disabled; the helper writes a private `0600` file and exposes no value in arguments or output. Tell the Owner the exact attach command and end the current turn without polling. Resume only after the Owner confirms entry is complete, inspect the result, then close only that specific window.

Store only the resulting metadata/path in `runtime.credentials`, with the Owner request and version reference. Ask the Owner whether to destroy it after version completion and record the decision. Saved files do not change the direct-TTY rule for interactive sudo authentication or permit `sudo -S`, askpass, environment or file-based password injection. Use this command as that window's command; its arguments contain metadata only:

```sh
python3 "$SKILL_DIR/scripts/save-credential.py" \
  --file "$CREDENTIAL_FILE" --repo-root "$PROJECT_ROOT" \
  --owner-request "$OWNER_REQUEST_ID"
```

The RUNBOOK defines the recorded metadata and retention decisions.

## Begin version monitoring

At the start of an authorized version with `currentVersion.status: implementing`, register the verified Manager's Paseo ID with the **existing** watchdog. Discover its execution host, executable, configuration, installed SDK and user units. Inspect [the supported contract](references/installed-contract.md) and actual implementation; deploy the compatible implementation through the authorized host procedure when the installed version lacks the new CLI.

The watchdog reads its own config and observes only that Manager through Paseo. It never reads `runtime.json`. The Manager reads its own runtime and checks missions, Workers, Reviewers, resources and guard requests when woken. A busy Manager is skipped; an idle Manager is woken each tick. Runtime expectations do not change this observation rule.

On the watchdog execution host, set verified `WATCHDOG_EXECUTABLE`, `WATCHDOG_CONFIG`, `WATCHDOG_CLIENT` (the SDK on that host), `MANAGER_PASEO_ID` and `MANAGER_DAEMON` values, then run:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" \
  --register "$MANAGER_PASEO_ID" --paseo-host "$MANAGER_DAEMON"
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --check --client "$WATCHDOG_CLIENT"
```

For a watchdog on Spark observing a Manager on u4090, open the verified SSH connection to Spark and use its supported Paseo SSH URI for `MANAGER_DAEMON`, such as `ssh://code2hack@u4090`. Verify that Spark can reach that daemon and its exact Manager ID. No checkout mount, copied runtime or Spark access to the Manager's runtime path is required. The existing shared interval and all other targets remain intact; legacy `runtimeFile` fields are preserved and ignored. Registration is idempotent by exact Paseo ID.

After target-count changes, use the installed renderer and verify readiness:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --write-units --client "$WATCHDOG_CLIENT"
systemctl --user daemon-reload
systemctl --user enable --now manager-watchdog.timer
systemctl --user restart manager-watchdog.timer
systemctl --user is-enabled manager-watchdog.timer
systemctl --user is-active manager-watchdog.timer
systemctl --user show manager-watchdog.service -p Result -p TimeoutStartUSec
systemctl --user list-timers manager-watchdog.timer
```

Read each `--check` result, not just its exit status. It is read-only and never sends. A oneshot service can be inactive between ticks. Record exact target/daemon, service/timer state and checked identity. Fixture success does not establish live notification receipt.

Guard approval timing belongs to the Manager's RUNBOOK procedure. An unanswered request at the configured deadline becomes a recorded `status: denied`, `resolution: timeout` decision, with decision/dismissal timestamps and `askOnNextWatchdog: true`, and ceases blocking the mission. Ask the Owner again at the next watchdog tick with a new correlated Request-ID, preserving the prior denial. After persisting that request and recording transport acceptance, clear the prior record's reask flag; retain its denied status, resolution and decision timestamps. The watchdog only wakes the Manager; it does not decide or track proposals.

## Handoff and end version

For handoff, preserve the outgoing participant and transfer current version, missions, pending correlations, reservations, guard decisions, credential references and cleanup duties. Verify the successor's receipt and native/Paseo binding; settle the outgoing controller. Supply a private JSON input with the exact current `previousManager`, a `successor` tuple `{host,runtime,id,paseo}`, and an absolute readable `record` path containing the handoff evidence. The successor runs `register` with `--handoff "$HANDOFF_JSON"`. This replaces the sole Manager and promotes an already registered successor without duplicating its native identity. Other participants and operational records remain intact.

On the watchdog host, remove the outgoing exact ID and register the successor's ID/daemon. Preserve the shared interval and unrelated targets, then run `--check`. These are separate atomic operations; record and reconcile the handoff before declaring monitoring ready.

At version completion, record completion in the runtime/version record and remove its Manager from monitoring:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --unregister "$MANAGER_PASEO_ID"
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --check --client "$WATCHDOG_CLIENT"
```

Removing an absent ID is idempotent; removing the final ID leaves a valid empty list. This does not retire the shared service or remove any other project's Manager. Rerender units after the target-count change. Resource teardown and preserved evidence follow AGENTS; discretionary cleanup and archival follow the recorded version-completion boundary.

## Fixture checks

```sh
node --test "$SKILL_DIR/tests/manager-runtime.test.mjs"
python3 -m unittest discover -s "$SKILL_DIR/tests" -p 'test_*.py'
node --test "$PROJECT_ROOT/tools/manager-watchdog/main.test.mjs"
```

Use isolated runtime/config/SDK fixtures. Do not register a fake live Manager, change active services or touch physical devices to validate setup.
