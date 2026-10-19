# Workflow audit

Historical bootstrap evidence: the current governance/runtime changes are recorded in [the 2026-10-20 workflow update](version-workflow-update.md).
Audit snapshot: 2026-10-19 UTC. Scope: current accepted remote governance, its relevant decision history, installed runtime consumers and the explicit initialization assignment `OWNER-EYEPROJECTOR-INIT-02`. EyeProjector uses independent product governance and history; source links below establish provenance rather than an ongoing organizational dependency.

## Authoritative evidence

- Remote `main`: `8929241f348bae9005b9b6bc1a93fbabd47145c1`; accepted implementation branch: `b3c9aa95f344dd5be8601baa8efd2d4bec40dc02`. Their `AGENTS.md` bytes are identical. Read [current governance](https://github.com/code2hack/EyeBrowse/blob/8929241f348bae9005b9b6bc1a93fbabd47145c1/AGENTS.md) and [current technical procedures](https://github.com/code2hack/EyeBrowse/blob/8929241f348bae9005b9b6bc1a93fbabd47145c1/DEV.md) directly from GitHub, rather than an old worktree.
- Accepted revisions include [judgment-based Expert escalation](https://github.com/code2hack/EyeBrowse/commit/edcbcea3c4048472077a82f4fc0081c9a4c91b56), [direct execution without ticket-plan approval](https://github.com/code2hack/EyeBrowse/commit/3d902c7408dd47eba58540037d316be0e16810e3), [completion-boundary cleanup](https://github.com/code2hack/EyeBrowse/commit/9f7f916340d3d7cb2a0e0a00e6cf4f147a5da43e) and [private registry/shared watchdog publication](https://github.com/code2hack/EyeBrowse/pull/51). The latter records exact-candidate Owner approval and verified merge `ad2076c5dde9d9ef7838fa8d1ce24b2ae63f3a03`.
- Current Owner instructions authorize preparing the complete repository initialization, retain independent exact-head review/document publication gates, require persistent-TTY sudo password entry, and require actual-host/native-thread commit provenance.

## Mechanism decisions

| Mechanism | Disposition | EyeProjector responsibility and verification |
| --- | --- | --- |
| Owner authority and canonical sources | Retained | Manager records authenticated decisions; requirements, governance, recipes, runtime and run history each have one defined home |
| Protected documents | Simplified | Planner owns `SPEC.md`/`AGENTS.md`; Manager owns `RUNBOOK.md`; explicit delegated initialization preparation is allowed; exact-candidate Owner approval protects `AGENTS.md` and replacement engineering procedures before `main` |
| One project-wide Manager and role boundaries | Retained | Manager coordinates; Planner defines product/tickets; Worker implements; scoped Expert hands back; independent Reviewer evaluates the candidate |
| Version plans and canonical issues | Simplified | Owner requests/authorizes future versions; execute authorized issues directly; bootstrap is not a release version |
| Separate ticket-plan gate | Excluded | Current accepted governance removes it. An older procedural phrase about reading a “ticket plan” does not reinstate approval or block authorized issue execution |
| DAG, concurrency and batches | Retained | Manager records actual prerequisites, Owner concurrency, resources and gates; confirms every startup; closes the current batch before the next unless scheduling is explicitly changed |
| Event-driven orchestration | Retained | Agents proactively report outcomes; Manager handles events and specific failure recovery rather than routine progress polling |
| Agent wall-clock budget | Excluded | No overall agent deadline; finite command/test limits and actual product/resource/security bounds remain |
| Todo identity and attempts | Simplified | One durable objective/history; local checklist is a projection; helper/reviewer reports from one correction round do not multiply failures |
| Expert trigger and return | Retained | Manager judgment uses durable history; notify Owner informationally; scoped verified handback returns later todos to Worker |
| Local creation/import/control | Moved to skills | Paseo remains lifecycle owner and Owner visibility surface; verify native identity, project/workspace/CWD and actual profile; isolated worktrees and one write owner remain governance |
| Agent-message envelope | Retained | One canonical definition in `AGENTS.md`; stable request identity, native tuple and separate transport identity; receipt/result/verification/acceptance remain distinct |
| Detailed SDK and remote-conversation recipes | Moved to skills | Use installed qualified interfaces, preserve pending work/prompts, reconcile uncertainty and qualify changed behavior; avoid duplicating mutable SDK examples in governance |
| Private live registry | Retained | `runtime.json` is Git-ignored, schema-compatible and atomically revised by coordinated maintainers; actual observed lifecycle remains separate from expected `running`/`idle`/`waiting` |
| Watchdog initialization | Moved to skill | Reuse installed shared watchdog; validate exact Manager/runtime binding and host accessibility; preserve other project entries/interval; fixtures do not activate a fake Manager |
| Physical resources and security | Retained | Manager owns reservations; test actor verifies serial/model and scoped grants; teardown/resource handoff is immediate; target-specific evidence supports only exercised claims |
| Android build/device recipes | Moved to runbook | Portable commands and exact tool/dependency pins belong in `RUNBOOK.md`; endpoints, reservations, secrets and live identities stay local |
| Human notifications and privileged operations | Simplified | Distinguish information from gates, bind decisions to authenticated request/candidate, reuse same-run decisions; current Owner TTY/password procedure overrides older generic terminal suggestions |
| Cleanup, archival and succession | Simplified | Record run/version completion boundary, defer discretionary cleanup, preserve evidence/children before archival; controlled sole-Manager replacement retains outgoing history and cleanup obligation |
| Historical migration-policy transitions | Excluded | Active documents state the accepted policy once; original decisions remain linked history rather than duplicated obsolete instructions |
| Unrelated application infrastructure and device grants | Excluded | No browser engines, proxy/subscription infrastructure, historical app trust hashes, device-specific credentials or prior project's assignments enter EyeProjector's contract |

## Installed runtime and monitoring findings

The installed watchdog executes on **Spark**, not u4090. Inspected entry points are `~/bin/manager-watchdog` and `~/.local/lib/manager-watchdog/main.mjs`; the latter's SHA-256 was `eb1c3b454997347a17de4a43acbc2b0c7852bc76cb7b0310776353fe9282bce4`. Configuration is `~/.config/manager-watchdog/config.json`. Its `intervalSeconds` was 600 and it held two existing project registrations; both were preserved.

The real consumer requires schema version 1, positive revision, exact `project.root`/runtime-file location and verified Paseo project binding. It validates unique native tuples, role/name and transport IDs, expected `running`/`idle`/`waiting` values and exactly one configured Manager. It rejects a lifecycle field. It checks actual native session, raw Paseo project/workspace/CWD and model/effort/mode rather than trusting display titles. Registration helpers must meet those consumer contracts; descriptive JSON alone does not execute a workflow.

Read-only `manager-watchdog --check` at `2026-10-19T09:09:20.933Z` reported `checkOnly: true` and no anomalies for the two existing projects. The user timer was enabled/active; its completed oneshot service was inactive with `Result=success`. The service's existing 95-second start bound reflects an older unit rendering: use the watchdog's supported `--write-units` plus user-systemd reload when a real registration change requires reconciliation, preserving the configured interval. This inspection did not send wake messages or alter registrations.

Spark could not read the u4090 EyeProjector checkout at the assigned repository path, and its daemon cannot assume a local u4090 Paseo binding. Live registration therefore requires an explicitly accessible runtime file and matching verified watchdog/Paseo host arrangement. The packaged skill reports this dependency rather than marking an unreachable path monitored or installing another scheduler. The initialization actor prepares/tests the skill without fabricating or activating a Manager.

The original procedures correctly distinguish API acceptance, registered expectations and actual execution. They also report limitations: ordinary send defaults can interrupt, unarchive or disturb pending permissions; a finish notice can be truncated; simple creation/message checks do not qualify every restart/archive race. EyeProjector retains those boundaries and requires affected capability checks before use, without asserting that shared-account routing is a security boundary.

## Publication and cutover

The Owner assignment authorizes branch preparation and routine bootstrap execution. Independent review must cover the exact current candidate. The retained document gate requires explicit Owner approval of the exact `AGENTS.md` and replacement `RUNBOOK.md` candidate before `main`; a changed candidate needs renewed approval. Local registry maintenance and ordinary reviews do not need separate Owner ceremonies.

The pending implementation issue/PR remains under its verified Manager/Worker and device reservation until coordinated handoff. Refresh accepted source, pending head, reviews and ownership again before publication; a promising open candidate is not an accepted baseline. Exact implementation reconciliation and verification are recorded in [the initialization audit](../migration/initialization-audit.md). No historical closed issue, run or live device grant is silently recreated as an active EyeProjector assignment.
