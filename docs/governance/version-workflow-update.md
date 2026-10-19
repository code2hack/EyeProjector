# Version workflow update — 2026-10-20

Authority: the Owner's requested governance changes and final timeout correction in the active EyeProjector session. The candidate updates AGENTS, RUNBOOK, the runtime skill and the existing shared watchdog together. Exact-head review and protected-document publication approval apply to this changed candidate.

| Area | Current contract |
| --- | --- |
| Responsibility | Manager owns specification, version planning, issues, scope/design/acceptance and orchestration; Worker implements; independent Reviewer evaluates |
| Scheduling | Owner and Manager plan a version; one integration branch; small independent issues each have a Worker, Reviewer and issue worktree; eligible missions progress concurrently within agreed resources |
| Retirement | Reviewer retires after the issue finishes; Worker and issue worktree remain until whole-version completion |
| Runtime | RUNBOOK is the canonical schema-2 specification; Manager generates the private host-specific registry with participants, version, ongoing missions, exclusive reservations, guard decisions and credential references |
| Watchdog | Version-start registration and version-end removal by exact Paseo ID; each tick skips busy Managers and wakes available idle ones through Paseo |
| Progress | Manager reads its own runtime, observes Workers/Reviewers, updates mission activity and briefly reports progress to the Owner |
| Guard timeout | Unanswered proposal is denied and dismissed; record it in runtime and ask the Owner again at the next wake with a new correlated request |
| Credentials | Retention requires the Owner's explicit request, direct-TTY matching no-echo entries and private external files; runtime stores references; Owner decides destruction after the version |

The new watchdog observes Managers only. It needs a Paseo route to each Manager rather than a readable remote project directory. Existing config entries, extra metadata and the shared interval remain intact; legacy runtime-file metadata is ignored. Its native register/unregister commands own atomic target updates and permit an empty list. The skill uses these commands rather than a second config writer.

The credential helper was tested with synthetic values only. It creates mode-0600 files outside the checkout, refuses an existing destination, checks matching entries and emits metadata only. No actual credential was captured or stored by this change.

Verification and installed-host rollout are recorded in [workflow-checks.json](workflow-checks.json) and the linked PR. Earlier initialization reports retain their original candidate-specific test counts, findings and installed implementation observations as dated history.

## Installed rollout

The compatible implementation was installed on the existing Spark watchdog. Read-only validation observed an idle Manager eligible for a wake and a busy Manager deferred. Both existing registrations were preserved. The Owner then set the shared interval to **1200 seconds**; regenerated user units show a 20-minute interval, active/enabled timer and a 170-second service command bound for two targets. The watchdog source is SHA-256 `347e4ccbffd2011c3d536c871553b934fb1781f736b07ce4d9d734430f0cb3d5`.

The first installation attempt rolled back because the unit renderer rejected `@` in the actual scoped SDK path. The narrow path fix, actual-path regression and independent rendering check passed before the successful retry. Private backups retain the previous executable, module, units and configuration. No real credential was captured; EyeProjector has no implementing version or live monitoring registration. Live scheduled heartbeat receipt/progress reporting has not been observed in this update's read-only qualification.
