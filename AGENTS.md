# EyeProjector agent governance

## Authority and canonical records

`code2hack` is the Owner and final human authority. The Manager records Owner decisions with their authenticated origin, request, scope and affected version or issue. Current Owner direction takes precedence over agent decisions and automation.

| Source | Owns |
| --- | --- |
| [SPEC.md](SPEC.md) and accepted design decisions | Product requirements and acceptance |
| `AGENTS.md` | Agent responsibilities, coordination and publication |
| [RUNBOOK.md](RUNBOOK.md) | Engineering procedures and the canonical `runtime.json` specification |
| Agent skills | Reusable operational procedures |
| Private root `runtime.json` | This host's participants, ongoing missions, reservations and pending decisions |
| Version plans, issues and run records | Authorized scope, assignments, decisions, evidence and history |

Refresh relevant remote branches, exact commits, issues and decisions before assigning work or accepting results. The Manager resolves conflicts and supplies current governance to assigned worktrees.

## Document ownership

The Manager owns product specifications, version plans, canonical issues and material scope, design and acceptance decisions, in discussion with the Owner.

| Document | Publication authority |
| --- | --- |
| `SPEC.md` | Explicit Owner instruction or approval |
| `AGENTS.md`, `RUNBOOK.md` | Explicit Owner edit request and approval of the exact candidate before `main` |
| Version plan | Owner and Manager discuss the version; Owner authorizes its implementation scope |

Prepare protected-document candidates on a branch and record the request and exact-candidate approval. Changed candidates require renewed approval. Routine implementation, review, runtime maintenance and authorized operations use their existing assignments.

## Roles

| Role | Responsibility |
| --- | --- |
| Manager | One project-wide coordinator: product specification, version planning, issues, dependency graph, assignments, resources, runtime, progress, review, integration and closeout |
| Worker | Implement one assigned issue in its dedicated worktree, verify it and deliver the exact candidate and evidence |
| Reviewer | Independently assess the issue's exact candidate and evidence, report a verdict and retire when the issue finishes |

Role and name identify an assignment; native identity and Paseo ID identify the actual session and transport. Review independence requires a session that did not author the candidate.

## Versions and issue dispatch

The Owner and Manager discuss each version's goals, scope and acceptance. The Manager records the authorized version plan, dedicated integration branch, dependencies, completion boundary and evidence location. Split the plan into the smallest coherent issues that can be implemented and verified independently, allowing concurrent missions where dependencies and resources permit.

Each issue states its objective, scope, acceptance, decision references and `Blocked by:` dependencies. It has one Worker, one independent Reviewer, a dedicated issue branch and worktree based on the version branch. PRs target that version branch. The Manager integrates accepted issues and verifies the assembled version before its final publication.

Before implementation, the Manager establishes the Owner's concurrency choice, foreseeable human gates, written-notification and alarm policy. Reuse valid decisions for the same version. Record them in the version record and local runtime.

At version start, the Manager registers its verified Paseo ID with the existing `manager-watchdog` and verifies the configured route and timer. Use the [manager-runtime skill](.agents/skills/manager-runtime/SKILL.md). The registration lasts through version implementation and is removed when that version completes.

For each ready issue, record the mission, Worker, Reviewer when assigned, exact branch/base, worktree, resources, evidence location and current activity in `runtime.json`. The assignment includes those references and the reporting route. Confirm native/Paseo/project/workspace/profile bindings and a correlated startup receipt before relying on an agent's work.

Dispatch eligible issues up to the agreed concurrency and fill available slots as dependencies and resources permit. Agents proactively report candidates, completion, failures, blockers and decisions needed. The Manager handles these events, maintains the mission list and keeps each mission's activity description current. Individual commands and tests retain bounded execution and applicable product requirements.

## Identity, runtime and lifecycle

Local agents are Owner-visible Paseo-managed instances in the verified project. Paseo owns their creation, control, restart and retirement. Each native session has one process/controller, and each issue has coordinated source ownership in its assigned worktree.

The Manager generates and maintains the private, Git-ignored `runtime.json` from [the RUNBOOK runtime specification](RUNBOOK.md#runtime-registry). Its contents are platform- and host-specific. Agents use the authoritative runtime path supplied in their assignment. The Manager preserves identity, assignments, pending requests and history through runtime/model changes and handoff.

Expected execution state and observed Paseo lifecycle are separate facts. The Manager reconciles observations with assignments and records decisions before changing expectations. Registry updates use coordinated, revisioned, validated atomic writes.

After an issue is accepted or otherwise completed by Owner decision, preserve its review evidence and retire its Reviewer promptly. Retiring the Reviewer releases that agent; a shared Worker workspace remains with the Worker. The completed Worker becomes idle, retaining its issue worktree until the whole version's implementation and verification finish.

At version completion, the Manager removes its watchdog registration, archives the retained Workers and their issue workspaces, verifies cleanup, and records the outcomes. Preserve required source, evidence and unresolved Owner decisions before archival. A Manager handoff transfers the version plan, missions, reservations, requests and cleanup duties to one verified successor, then replaces the watchdog registration.

## Watchdog wake and progress reporting

Every watchdog tick checks the registered Managers through Paseo. A busy Manager is skipped. An available idle Manager receives a wake to inspect its own runtime and its Workers and Reviewers.

On that wake, the Manager observes every ongoing mission, collects available outcomes, reconciles blockers and briefly reports progress to the Owner: which issues are active, what each Worker is doing now, what is under review and what needs an Owner decision. It also processes expired guard proposals and asks the Owner about recorded timeout denials flagged for the next watchdog wake. Update the runtime and dispatch any newly eligible work after handling the results.

The watchdog observes Managers and delivers wakes. The Manager owns mission observation, scheduling, runtime updates and progress reports.

## Communication contract

Direct agent messages use the registered role/name and native identity:

```text
From: <Role>: <name> | host=<host>; runtime=<runtime>; id=<id>
To: <Role>: <name> | host=<host>; runtime=<runtime>; id=<id>
Request-ID: <stable-message-id>
In-Reply-To: <answered-request-id, when applicable>
```

Use the verified Paseo interface for local messages. Resolve actual identity, project/workspace/CWD, assignment and archive/permission state before routing. Preserve busy work and pending human prompts. Watchdog wakes identify their infrastructure source and exact recipient separately from peer messages.

Persist message intent, exact body, stable request/transport IDs and pending state before sending. Record transport acceptance, recipient receipt, complete result, evidence verification and review acceptance separately. Reconcile uncertain delivery before retrying the same logical request. Preserve request correlations across reconnects and Manager succession.

Workers and Reviewers report material outcomes proactively to the Manager. An actionable GitHub post also receives direct notification. Owner decisions use the authenticated human channel and remain bound to their actual request, operation and candidate.

## Verification, Git and review

Workers run relevant available checks and push reviewable candidates. Reports identify issue, branch/worktree, exact remote SHA, changed scope, results, evidence and remaining limits. Distinguish host tests, physical measurements, Owner observations and inference. Shared protocol changes assess both Phone and Glasses consumers.

The Reviewer evaluates the exact base/head against the issue and records `PASS`, `CHANGES_REQUESTED` or `BLOCKED`. Each changed head needs a renewed verdict and affected verification. The Manager checks current remote identity, evidence, dependencies and publication gates before integrating the issue, verifies the merge, records acceptance and closes the issue. Version acceptance covers the assembled version branch.

Public issues, PRs, comments, reviews and commit messages use `<Role>: <name>` attribution. Codex-created or amended commits append exactly one actual `Codex-Host: <hostname>` and `Codex-Thread-ID: <thread-id>` trailer, resolved from host/session metadata.

Use at most one workflow-state label: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human` or `wontfix`. The Manager owns issue readiness and execution transitions. Resume the same assignment when its dependency or Owner gate resolves.

## Physical and shared resources

The Manager explicitly records exclusive reservations for conflicting devices and shared services in `runtime.json`, including the mission, holder and authorized operations. Reserve every target of a cross-device operation and release reservations promptly when the work ends. Follow the assigned device route and existing Owner grants; record actual device identity and verification evidence with the mission.

Issue worktrees define source-write ownership. Shared Git metadata, external services and other workspaces retain their coordinated ownership. Preserve installed applications, private state and live sessions according to the assignment.

For interactive sudo, resolve the command and targets, create a unique window in the persistent `sudo` tmux session and let the Owner authenticate directly in that TTY. Give the exact attach command and end the turn while input is required. Resume after Owner confirmation and close only that operation's window after recording its result.

When the Owner explicitly requests credential retention, use a separate window in the same `sudo` session for direct no-echo double entry. Save matching entries in dedicated private credential files outside the repository and record only their references in `runtime.json`. The [runtime skill](.agents/skills/manager-runtime/SKILL.md) supplies the capture procedure. At version end, ask the Owner whether to destroy those files and record the decision and outcome.

## Owner approval of added guards

Before adding a safeguard, security guard or device-verification guard in product code, tests or operations, the proposing agent explains the restriction, reason, effect on work and proposed verification to the Owner and obtains approval. Record the request and its deadline using the runtime's configurable approval timeout.

If no answer arrives by the deadline, treat the proposal as denied, dismiss it and continue work without adding that guard. Record the timeout denial in `runtime.json` and flag it for an Owner question on the next watchdog wake. A renewed question uses a new correlated request while retaining the prior denial. Other explicit Owner decisions retain their scope.

Provide written notifications for material outcomes and concrete Owner decisions. Use the version's approved alarm policy. Bind approvals to their actual request and candidate, then record the performed action and verified result.
