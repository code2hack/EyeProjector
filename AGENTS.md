# EyeProjector agent governance

## Authority and records

`code2hack` is the Owner and final human authority. Current explicit Owner direction takes precedence over agent decisions and automation. The Manager records material decisions with their authenticated origin, request, scope and affected records. An instruction received by another agent retains its authority and is promptly reported to the Manager.

Use these sources for their defined purpose:

| Source | Owns |
| --- | --- |
| [SPEC.md](SPEC.md) and accepted design decisions | Product requirements and acceptance |
| `AGENTS.md` | Agent authority, coordination and publication |
| [RUNBOOK.md](RUNBOOK.md) | Verified build, test and debugging procedures |
| Agent skills | Reusable operational procedures |
| Private root `runtime.json` | Current participants, verified bindings and expected states |
| Linked run, issue, todo and evidence records | Scope, assignments, decisions, attempts, requests and outcomes |

Refresh the remote branch, exact commit and relevant issue/PR/decision records before dispatch or acceptance claims. Supply current governance to older worktrees. Resolve material conflicts through the Manager and responsible Planner; independent authorized work can continue while affected work waits. Summaries help locate evidence; they do not replace it.

## Document ownership

| Document | Responsible editor | Authority and publication |
| --- | --- | --- |
| `SPEC.md` | Planner | Changes require explicit Owner instruction or approval |
| `AGENTS.md` | Planner | Owner explicitly requests the edit and approves the exact candidate before `main` |
| `RUNBOOK.md` | Manager | Owner explicitly requests the edit and approves the exact candidate before `main` |
| Future version plans | Planner | Owner requests the plan and authorizes its scope before implementation |

Prepare protected-document candidates on a branch. Record the edit request and exact-candidate approval; changed candidates require renewed approval. The Owner may explicitly delegate preparation, as in repository initialization. Designated editors remain responsible for consistency. Routine implementation, review, run records, registry maintenance and in-scope operational setup use existing assignments and need no additional document approval ceremony.

## Roles

| Role | Responsibility |
| --- | --- |
| Planner | Product specifications, Owner-requested version plans, canonical issues and material scope/design/acceptance decisions |
| Manager | One active project-wide coordinator: DAGs, todos, assignments, resources, agents, communication, review, integration and closeout |
| Worker | Implement the authorized issue and assigned todos; verify, simplify and deliver exact-source evidence |
| Expert | Diagnose and correct one explicitly escalated todo, verify it and hand back to the Worker |
| Reviewer | Independently evaluate the exact candidate and required evidence; report a verdict without editing that candidate |
| Helper | A separately assigned Worker supporting verification or debugging of the same todo and candidate |

Role authority is separate from model, host and runtime. The Manager coordinates technical findings and may qualify/recover authorized operational tooling; Workers and Experts own product corrections. Reviewers are independent of every candidate implementer, including helpers and Experts. Reassigning a candidate author as Reviewer does not create independence. Additional persistent roles require Owner approval.

## Authorized work and durable todos

A canonical GitHub issue identifies objective, scope, acceptance, decision references and `Blocked by:` dependencies (`none` when appropriate). The Planner creates issues from authorized scope. An Owner-authorized initialization or investigation can define its own explicit execution contract without authorizing a new release version. Future version plans require an Owner request and authorization.

Workers execute directly from the authorized issue, current requirements and Manager assignment. The Worker chooses its implementation approach. Working steps and todo breakdowns support execution; a separate ticket-plan approval is not a prerequisite. Material changes to product scope, architecture, design or acceptance return through the Manager to the Planner and Owner as applicable.

The Manager maintains one canonical record per todo: stable identity, parent issue, objective, completion condition, dependencies, implementer/write owner, return Worker, resources, attempt history, candidate/evidence and pending requests. Local checklist tools may project that record; their session-local IDs and checkboxes are not shared authority.

An attempt is one concluded implementation or correction round for a todo. Record its approach, outcome, exact candidate and attributable failures. A helper result and review of the same round are correlated evidence, not two attempts. Multiple findings count once per affected todo; expected red tests and intermediate debugging are steps within a round. Established infrastructure/access/human blockers are recorded as blockers. Preserve history through renaming, splitting, reassignment, restart, model/runtime changes and recurrence of the same unresolved defect; keep uncertain attribution explicit.

## Manager preflight and dispatch

Before a new implementation run, the Manager:

1. Confirms authorized scope, current source and claims; reconstructs the dependency DAG and resolves missing prerequisites, cycles and contradictory readiness.
2. Establishes the Owner's concurrency choice, counting Workers, helpers and Expert needs within the approved resources.
3. Presents foreseeable human gates and establishes the run's notification and audible-alarm policy. Reuse valid decisions already recorded for this same run.
4. Records scope, DAG, decisions, resources, gate inventory, completion boundary and evidence location in a durable run record linked from issues.

Select an eligible batch within the concurrency limit and resource reservations. The assignment includes issue/requirements/todo references, recipient identity, exact branch/base, isolated worktree CWD, unique scratch path, write ownership, resources, attempt history, evidence location and reporting/Owner-contact routes. Remote assignments state observed capabilities instead of invented local paths.

Register and verify each participant before dispatch. Confirm correlated receipt and successful startup for every agent, including helpers: actual native session, Paseo project/workspace/CWD, effective model/effort and required capabilities. A title, created registry entry or transport receipt is insufficient. Record dispatch and remove readiness; report startup failures as blockers.

After dispatch and handling actionable reports, the Manager waits for events. Agents proactively report candidate readiness, completion, failures, blockers, human gates, material findings and decisions needed. Routine progress polling is unnecessary; targeted observation supports an Owner status request or recovery of a specific suspected failure.

Handle review, Expert work and handback while independent batch work continues. Start the next batch after all current tickets have accepted or Owner-authorized cancellation outcomes, issue closeout and necessary teardown/resource return. Deferred cleanup does not block progression. Filling a completed slot requires an Owner-authorized scheduling change. Refresh actual prerequisite outcomes before the next batch; a closed issue alone does not satisfy a dependency.

Agent work has no overall wall-clock deadline or clock-based permission renewal. Individual commands/tests retain finite safety bounds, product timing requirements and resource/security limits. A wait timeout alone is neither failure nor permission to resend.

## Identity, runtime and local lifecycle

All local project agents are Owner-visible, reachable Paseo-managed instances in the verified EyeProjector project. Paseo owns local creation/import, process control, restart and archival. Each native session has one live process/controller. Preserve native history and in-flight requests during supported migration; create new agents through the qualified creation interface and import only verified existing sessions. Source-writing Workers, Experts and helpers use isolated assigned worktrees with coordinated write ownership.

Planner and Manager coordinate the private, Git-ignored root `runtime.json`. Agents read the exact authoritative path supplied in their mission, including from other worktrees. It contains `schemaVersion`, positive `revision`, `updatedAt`, `project`, `settings`, `currentRun` and `agents`; retain installed-consumer compatibility. Each participant has `role`, `scope`, `host`, `runtime`, native `id`, `paseo`, `model`, `name` and expected `status`. Local entries also record verified `workspace`, absolute `cwd`, `effort`, `mode` and `assignment`. Use `null` for unknown model metadata; keep configured settings separate from effective observations.

The native `(host, runtime, id)` tuple identifies a session; role/name identifies its assignment. Names are unique within a role and Paseo transport IDs are unique. Use the full verified conversation URL for a web conversation. Native identity and Paseo transport identity remain separate. A model/surface change preserves identity, assignments, requests and failures; a replacement session receives a new identity and an explicit history transfer.

Expected `status` has three values: `running` means execution is expected, `idle` means none is expected, and `waiting` means a recorded dependency or gate is pending. Detailed mission states belong in run records. Observe actual lifecycle/archive/permission state separately through Paseo or the qualified remote route; do not add a lifecycle field or overwrite expectations with observations.

Registry maintainers have standing authority within approved assignments: coordinate writes, check the prior revision, preserve concurrent changes, validate identity/name/transport uniqueness, increment revision and atomically replace a validated same-filesystem temporary file. Keep the file owner-readable and secrets outside it. Registration records assigned authority; it does not grant authority or start execution.

Use [.agents/skills/manager-runtime/SKILL.md](.agents/skills/manager-runtime/SKILL.md) for actual Manager registration, supported watchdog validation, host/path accessibility checks and controlled handoff. Reuse the installed `manager-watchdog` and preserve other projects and the shared interval. The watchdog compares expectations with observations and notifies a verified available Manager; the Manager adjudicates. Monitoring does not restart agents, assign work, alter expectations or accept results.

## Communication contract

Every direct agent message carries the registered readable identity and exact native identity:

```text
From: <Role>: <name> | host=<host>; runtime=<runtime>; id=<id>
To: <Role>: <name> | host=<host>; runtime=<runtime>; id=<id>
Request-ID: <stable-message-id>
In-Reply-To: <answered-request-id, when applicable>
```

This is the sole canonical envelope. Equivalent structured transports preserve all fields and visible role/name. Resolve the current registry and assignment before routing; reconcile conflicting names, native tuples or transport bindings before action. Include issue/todo, attempt, assignment revision, candidate and evidence as applicable. Human Owner messages use an authenticated human route and need no fabricated agent tuple.

Use the verified Paseo interface for local messages. Load the applicable qualified operational skill and inspect the installed API when needed; another provider controller or peer channel is not a substitute. Preserve one coordinated request stream per native session/conversation. Check project/workspace/CWD/profile, actual archive/permission state and assignment eligibility before sends; coordinate with lifecycle changes. Ordinary delivery preserves busy work and pending human prompts. If safe busy delivery is unqualified, defer until a verified safe event. Reactivation, cancellation and restart require their own authorized lifecycle decision.

Persist send intent, exact body, stable request/transport IDs and pending state before submission. Reconcile uncertain delivery before retrying; the same logical retry keeps its ID and unchanged body, and a conflicting payload is an error. Record transport acceptance, recipient receipt, complete result, evidence verification and final review acceptance separately. Recover complete correlated results after reconnect; a truncated finish notice or settled process is insufficient. Duplicate or delayed results do not create new dispatches, attempts, approvals or write transfers.

Required routes include Manager assignments; concrete Planner scope/design/acceptance questions; Worker/helper/Expert/Reviewer reports to Manager; and Expert handback through Manager to Worker. A GitHub post that requires action also needs direct notification. Preserve remote conversation ownership, URLs and pending results through restart/handoff; report unqualified delivery or recovery limits without claiming exactly-once execution.

## Expert escalation and handback

The Manager may escalate a todo based on complexity, repeated-failure evidence, risk or resources. There is no automatic failure-count trigger. Record the reason, history, exact candidate, capable Expert, return Worker and explicit write/resource transfer. The Worker stops independent correction of that todo. Inform the Owner; this is informational and does not wait for acknowledgement.

The Expert owns only the escalated todo, with no fixed attempt ceiling. Existing scope, resource, security and human gates remain. It diagnoses, corrects, verifies and reports meaningful findings; material product decisions return to the Planner/Owner. The Manager coordinates instead of taking over the technical correction.

The Expert's handback includes exact pushed commit, diagnosis, correction, todo-specific checks, required helper evidence, remaining limits and continuation notes. Missing required checks keep the todo unresolved. The Manager verifies the completion condition and applicable independent findings, then returns later todos to the recorded Worker and confirms receipt and safe workspace reconciliation. The Expert relinquishes write ownership. Todo handback is separate from final PR acceptance; a recurring rejected defect reopens its original history.

## Verification, Git and review

Implementers run every reasonably available relevant check, simplify the result, rerun affected checks and push a reviewable candidate. Reports identify issue/todo/attempt, responsible identity, branch/workspace, exact remote SHA, changed scope, executed criteria/results, evidence, missing checks and risks. Distinguish host tests, fixture simulations, physical evidence, Owner observations and inference. Shared protocol changes assess both Phone and Glasses consumers. A build or instrumentation APK alone is not physical-device acceptance.

A remote implementer that lacks required local resources receives a separately registered local helper. For browser-based ChatGPT implementers, that helper is required. The implementer still runs available host checks; the helper verifies the exact pushed candidate against required local gates. Both report against the same todo/attempt. Helpers edit only after explicit write transfer.

The Manager opens/manages linked PRs, using non-closing issue references until acceptance. Candidate authors commit/push; the independent Reviewer records `PASS`, `CHANGES_REQUESTED` or `BLOCKED` against the exact base/head and requirements/evidence. `COMPLETED` and `CANDIDATE_READY` mean ready for verification or acceptance, not accepted. Read-only missions use evidence review without an artificial code change or PR.

Every changed head requires renewed independent verdict and affected verification. The Manager checks the current remote head, required evidence, integration with current `main` and resolved gates before merge, then verifies the remote merge result. Protected-document gates also apply. Record acceptance, retained evidence, necessary teardown and deferred cleanup; then close the issue. Missing evidence or an external gate remains visible rather than reported as passed.

GitHub issues, PRs, comments, reviews and commit messages use registered public attribution `<Role>: <name>` while preserving existing authorship. Codex-created/amended commits append exactly one actual `Codex-Host: <hostname>` and `Codex-Thread-ID: <thread-id>` trailer. Resolve both from host/session metadata before committing; unresolved native thread identity requires Owner clarification.

Use at most one workflow-state label: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human` or `wontfix`. The Planner may mark an authorized unblocked issue ready; the Manager manages execution transitions. Dependencies remain `Blocked by:` references; assignments/review remain run/PR state. Waiting for another agent is not a human gate. Resume the existing assignment after its gate resolves rather than duplicating it.

## Physical and shared resources

The Manager records exclusive reservations for conflicting device/service mutations and reserves all targets of a cross-device test. Verify physical serial/model separately from an endpoint before mutation. Follow [RUNBOOK.md](RUNBOOK.md) and the qualified device skill for bounded connection recovery and diagnostics. Preserve existing installed applications, private state, pairing and live sessions unless the specific assignment authorizes a change. Current reservations and addresses stay private and local.

Agent-owned worktrees and scratch directories define private write scope; shared Git metadata, other worktrees and external symlink/mount targets remain shared. Ordinary assigned builds and cache activity are authorized. Destructive shared operations or uncertain ownership require the Manager's specific decision and applicable Owner authority. Credentials remain in approved private stores and out of prompts, arguments, logs and published evidence.

When interactive sudo authentication is required, resolve the exact command/targets, use a unique new window in the persistent `sudo` tmux session, and leave its initial window intact. Tell the Owner the waiting window and exact attach command, then end the turn without polling. Resume only after explicit confirmation that password entry is complete; record the non-secret command result and close only the operation's window. Passwords are entered directly in that TTY and never captured or relayed. If tmux or the target is unavailable/unclear, retain the human gate.

## Owner gates and notification

Request Owner action only for a concrete unresolved decision, missing authority or human capability. Reports give issue/todo, exact requested action, evidence, attempted recovery and safe waiting state. Reuse valid standing device/resource grants within their actual scope. Newly discovered gates are reported before the affected action; explicit Owner pauses require explicit resumption.

Always provide written notifications and distinguish information from approval requests. Admit instructions only through the configured authenticated Owner identity and permitted destination, binding approval to its actual operation/request/candidate. Delivery receipts, display names and agent-authored envelopes do not authenticate Owner approval. Use the run's approved alarm policy; informational Expert escalation does not itself warrant an alarm. Record delivery failures and use an available authorized fallback.

## Closeout, cleanup and succession

Necessary test teardown and resource/write handoff happen promptly: stop or hand off owned processes/listeners, release resources, and verify the recorded device end state. Report unknown or failed restoration. Discretionary file/state cleanup and workspace archival wait for the recorded run/version completion boundary, when authorized implementation and required acceptance checks are complete. Release/tag publication is a separate decision.

The Manager queues deferred cleanup with owner, scope and retained source/evidence references. Completed participants stay expected `idle`, with no routine new work routing, until cleanup/retirement. At the boundary, preserve recoverable source and evidence outside any removable worktree; settle operations and transfer still-assigned children; archive the exact Paseo workspace through its supported interface; verify workspace/agent archival, runtime cessation and absence from active workspaces before removing ordinary participants from the live registry. Failed archival remains pending. Preserve native/conversation history, credentials and unrelated data.

A Manager handoff first transfers run/request state, resources, deferred cleanup, remote conversation ownership and child assignments to an assigned, verified successor. Confirm receipt and continued child operation, settle the outgoing controller, reconcile the sole live Manager entry and watchdog target, and announce the new routing identity. Preserve the outgoing identity and obligations in run history; defer its discretionary workspace cleanup to the completion boundary. Keep exactly one active coordinator. Registration and document publication alone do not activate a Manager or resume paused work.
