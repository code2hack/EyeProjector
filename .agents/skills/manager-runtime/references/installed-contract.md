# Watchdog and runtime setup contract

The formal product runtime schema is maintained in the [RUNBOOK runtime chapter](../../../../RUNBOOK.md#runtime-registry). The skill helper initializes and reconciles that schema; the watchdog does not consume it.

## Existing host and compatible implementation

The existing shared watchdog is on Spark:

- Executable: `~/bin/manager-watchdog`, invoking `/usr/bin/node "$HOME/.local/lib/manager-watchdog/main.mjs"`.
- Implementation: `~/.local/lib/manager-watchdog/main.mjs`.
- Config: `~/.config/manager-watchdog/config.json`.
- User units: `manager-watchdog.timer` and `manager-watchdog.service`.
- Current shared interval: 1200 seconds, set by the Owner on 2026-10-20; retain the actual configured value.

The compatible source and fixture suite are [tools/manager-watchdog/main.mjs](../../../../tools/manager-watchdog/main.mjs) and [main.test.mjs](../../../../tools/manager-watchdog/main.test.mjs). Inspect the actual installed implementation before invoking its CLI; the older implementation's runtime-file/anomaly monitoring is replaced by this Manager-only contract. Installation is a host operation, separate from registering a version's Manager.

## Configuration and supported CLI

```json
{
  "intervalSeconds": 1200,
  "managers": [
    {"paseoId": "verified-manager-id", "paseoHost": "ssh://user@manager-host"}
  ]
}
```

`intervalSeconds` is a positive integer. `managers` may be empty. Paseo IDs are unique; `paseoHost` is optional and selects the SDK daemon route. Legacy `runtimeFile` and other extension fields remain intact and are ignored. No participant registry, mission or private runtime file is read by the watchdog.

Supported commands:

```sh
manager-watchdog --config "$WATCHDOG_CONFIG" --register "$MANAGER_PASEO_ID" --paseo-host "$MANAGER_DAEMON"
manager-watchdog --config "$WATCHDOG_CONFIG" --unregister "$MANAGER_PASEO_ID"
manager-watchdog --config "$WATCHDOG_CONFIG" --check --client "$WATCHDOG_CLIENT"
manager-watchdog --config "$WATCHDOG_CONFIG" --write-units --client "$WATCHDOG_CLIENT"
```

Registration updates the exact Paseo ID, preserves other projects/shared interval/extensions and is atomic/idempotent. Omitting `--paseo-host` for an existing target preserves its route. Unregistering an absent ID is a no-op; removing the last target leaves a valid empty list. The skill delegates configuration writes to these commands instead of maintaining another config adapter.

`--check` observes Manager status and reports whether a wake would occur, without messages or state writes. `--run` and the service perform live ticks; internal `--locked` belongs to the existing flock-protected execution. `--client ABSOLUTE_MODULE` or `PASEO_CLIENT` selects the installed SDK module when needed. Empty checks and config-only operations need no daemon connection. Unit rendering retains the shared interval and sizes the service command bound for target count; rerender/reload after count changes.

A busy Manager is skipped; an available idle Manager receives a fresh heartbeat each tick. The heartbeat asks the Manager to read its own runtime and review ongoing missions, Workers, Reviewers and pending guard questions, then report progress. API acceptance, recipient receipt and work acceptance remain separate. Correlated uncertain-send records are reconciled before another delivery; registration is not a replay or acceptance decision.

## Paseo routing and host access

Qualified SDK installations:

- u4090: `/home/code2hack/.nvm/versions/node/v24.20.0/lib/node_modules/@getpaseo/cli/dist/utils/client.js`.
- Spark: `/home/code2hack/.npm-global/lib/node_modules/@getpaseo/cli/dist/utils/client.js`.

Discover the current installation rather than copying these paths into another host's config. Native identity inspection uses `fetchAgent` and `fetchWorkspaces`; `runtimeInfo.sessionId` or `persistence.sessionId` is the native ID, separate from Paseo agent ID. CLI inspect alone omits this binding. The local runtime helper resolves the SDK's `getDaemonHost()` and `resolveDaemonTarget(host)`, accepts local IPC/loopback only and pins that exact host when connecting, so remote results cannot be labeled with the current machine's hostname.

The watchdog intentionally supports another host's daemon through explicit `paseoHost`. Spark can observe a u4090 Manager via its verified `ssh://user@u4090` Paseo route. Verify SSH reachability, SDK support and the exact Manager binding from Spark. Spark does not need the u4090 checkout or runtime file; a mount or copied registry is unnecessary. Keep credentials in approved private stores rather than URI arguments or published evidence.

## Runtime preparation boundaries

The skill initializes schema 2 from the RUNBOOK, preserving operational collections and unknown extensions on reconciliation. Older schema conversion requires a retained private source snapshot and an explicit Manager-authored migration. Other projects' schemas remain untouched because the shared watchdog no longer validates them.

A real Manager assignment and authorized implementing version precede live monitoring registration. At version end remove that exact ID; retain other projects. Setup fixtures do not start a version, activate a Manager, accept product results or demonstrate physical behavior. No live registration is needed to validate the helper or watchdog contract.
