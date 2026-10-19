# Initialization audit

Historical bootstrap evidence: the current governance/runtime changes are recorded in [the 2026-10-20 workflow update](../governance/version-workflow-update.md).
Owner authority: `OWNER-EYEPROJECTOR-INIT-02`. Tracking: [EyeProjector #1](https://github.com/code2hack/EyeProjector/issues/1); [run record](../runs/initialization.md). This report distinguishes repository readiness from physical product acceptance and live Manager monitoring.

## Source and destination

The physical permanent workspace is `/home/code2hack/Projects/EyeProjector` on **u4090**, established with `pwd -P`. It was empty and not a Git repository. `gh repo clone code2hack/EyeProjector .` cloned `git@github.com:code2hack/EyeProjector.git` directly here. Destination `main` was refreshed and remained `523ebc8d6ef294030b43ebab85df90fd789606f4`. Initialization uses `work/init-eyeprojector`, with independent ancestry rooted at that existing destination commit.

The accepted source snapshot is `b3c9aa95f344dd5be8601baa8efd2d4bec40dc02`; its latest implementation change is `ecb5b59ca152360ecaa6014ab1f815b58b62c6cd`. Source repository, paths, original identities, accepted historical reports, original artifact provenance and pending source/evidence are retained in [docs/provenance](../provenance/README.md). No donor ancestry, unrelated checkout, browser engine, proxy or subscription infrastructure was imported or modified.

| Source path relative to donor root | Destination |
| --- | --- |
| `experiments/fold6-rg-projector/directhost` | `directhost` |
| `experiments/fold6-rg-projector/directrg` | `directrg` |
| `experiments/fold6-rg-projector/protocol` | `protocol` |
| `experiments/fold6-rg-projector/hostprobe` | `hostprobe` |
| Experiment build/settings | Root build/settings |
| Root wrapper scripts and `gradle/wrapper` | Same paths |

All imported inputs have original SHA-256 entries in [snapshot-files.json](../provenance/snapshot-files.json). [host-checks.json](host-checks.json) binds the actual migrated build inputs, test/lint results and new APK hashes. Retained test-only `android.*`/`org.json` doubles intentionally model platform classes; production namespaces are `com.code2hack.eyeprojector.<module>`.

## Normalization and boundaries

- `rootProject.name=EyeProjector`; production/test imports, source directories, Android namespaces, application IDs, instrumentation target/runner and documented `app_process` class move together. Distinct application IDs are `com.code2hack.eyeprojector.{directhost,directrg,hostprobe}` and `com.code2hack.eyeprojector.directrg.test`.
- UI/diagnostic/thread labels and instrumentation result key use EyeProjector terminology. The host screen now describes required video **and audio**, correcting its stale video-only text. The receiver retains local head pointer, touchpad taps/Back/scroll/Disconnect, bounded audio/video and authenticated direct transport.
- Historical resource names and baked-in video text were replaced; both synthetic fixtures were regenerated with the original timing pattern and new text. The fixture's browser Custom Tab button was omitted; its owned input/media/tone and ordinary Display Settings compatibility fixture remain. Media hashes and generator/toolchain are recorded in provenance.
- APK version metadata is `1` / `bootstrap`, not a new release version. New app identities install alongside experimental apps with fresh private state. No app was installed, upgraded, cleared, uninstalled or reprovisioned, and no experimental private data or device session was touched.
- No pending panel-power code is promoted to the baseline. Existing no-overall-host-cutoff, `keep_active=true`, `power_on=false`, required Opus audio, mutual TLS and bounded failure safeguards remain.

## Documents and runtime skill

`SPEC.md` is a newly authored standalone product contract with glossary, intended behavior, compatibility limits and explicit acceptance evidence. `AGENTS.md` is focused orchestration governance (under 350 lines). `RUNBOOK.md` owns verified build/bootstrap/debug recipes, replacing the former development-procedures concept. README links these documents; no legacy development document or browser specification was copied.

[Workflow audit](../governance/workflow-audit.md) identifies current remote governance and accepted decisions, classifies retained/simplified/moved/excluded mechanisms, and records installed consumers. The Owner delegated preparation in this bootstrap; the retained protected-document gate requires approval of the exact `AGENTS.md`/`RUNBOOK.md` candidate before `main`.

The [manager-runtime skill](../../.agents/skills/manager-runtime/SKILL.md) discovers actual native/Paseo/project/workspace bindings, initializes/reconciles a private registry, updates existing watchdog targets atomically and supports explicit Manager handoff/retirement. It preserves existing settings, current run, pending requests/history, participants, monitoring interval and unrelated projects. Cooperative locks, expected-file checks and same-directory rename guard updates; registry/config handoff remains two serialized atomic operations, followed by validation.

The actual installed watchdog was inspected on **Spark**: implementation SHA-256 `eb1c3b454997347a17de4a43acbc2b0c7852bc76cb7b0310776353fe9282bce4`, two existing targets, shared interval 600 seconds. Its supported read-only `--check` reported no anomalies, and its timer was enabled/active; no live registration, unit or interval was changed. Spark lacks this u4090 checkout and its local daemon does not contain u4090's Manager binding. Actual activation requires a compatible file-access **and daemon-observation** arrangement. Merely adding an inaccessible path or mounting JSON does not solve that dependency.

This actor is the migration implementer. The helper's read-only inspect verified its real session/native/Paseo/project/workspace; it did not assign a role. No live EyeProjector Manager, runtime registry or watchdog registration was created. `runtime.json` and temporary registry files are ignored; fixture identities are isolated test data.

## Verification executed on u4090

Toolchain: OpenJDK 17.0.20.1; wrapper Gradle 8.11.1; AGP 8.7.3; Kotlin 2.2.21; compile/target API 35, minimum API 32; actual AGP-selected build tools 34.0.0. No dependency or platform upgrade was introduced.

| Check | Actual outcome |
| --- | --- |
| Full Gradle verification in RUNBOOK, plus empty `hostprobe:testDebugUnitTest` | Passed. Final run: 31 s, 216 actionable tasks (44 executed, 172 up-to-date); first complete run executed all 216 tasks and passed |
| App debug builds | `directhost`, `directrg`, `hostprobe` passed |
| Receiver instrumentation APK | Build passed; not executed on devices |
| Kotlin/JVM tests | **54 passed**, zero failures/errors/skips across nine suites; fixture-only app has no JVM tests |
| Lint | All four modules passed, zero errors. Five warnings: host/receiver `OldTargetApi`; fixture `OldTargetApi`, `DataExtractionRules`, `MissingApplicationIcon`; protocol none |
| Manifest and DEX identities | All four APK manifests checked; instrumentation targets exact new receiver; new `ShellMain` and runner descriptors exist; no old product package in DEX |
| Bootstrap/server | Official server fetched and SHA-256 matched source constant; unmodified 733,930 bytes. Per-session copy/hash tests passed |
| Dependencies/attribution | Actual app runtime graphs resolve only Kotlin stdlib 2.2.21, annotations 13.0 and `protocol`; wrapper/source hashes retained, upstream scrcpy license fetched at pinned source; [notices](../../third_party/NOTICE.md) included |
| Helper tests | **8 passed**: init/idempotency/conflicts, private atomic writes/interruption/concurrency, identity validation, multiple projects, CLI flow, handoff/promotion/retirement and local-endpoint rejection/pinning |
| Independent skill forward test | **23/23 passed** on the corrected helper in separate temporary repositories/SDK/config fixtures: the original 17 workflow cases plus six endpoint checks using real installed SDK resolver exports without real connections. Includes existing-Worker promotion, state preservation, stale reclaim/lock/gate refusal and monitor replacement/retirement |
| Installed-consumer compatibility | Generated fixtures passed the actual installed watchdog's exported runtime/config validators; no live writes or notification sends |
| Skill validation | `node --check`, skill-creator `quick_validate.py` and root read-only identity inspection passed |
| Media/tool scripts | Shell syntax passed; valid cached server accepted, corrupt existing server refused/preserved in an isolated fixture; regenerated media checked as 12 s H.264 480×640/AAC 48 kHz |
| Documents/terminology | Relative active links, shell syntax, command paths and old-product-identifier audit checked; authored-file whitespace passed. Original patch context, Windows-wrapper CRLF and upstream-license bytes are retained verbatim |

`aapt dump badging` could not resolve the inherited platform drawable icon without framework context; `aapt dump xmltree` successfully verified binary manifests, and DEX inspection verified executable classes. This host-tool limitation did not require changing app icons. Compiler deprecation warnings remain for inherited Android APIs. Build logs/test XML/lint reports stay in local build/evidence storage; hashes and APK sizes are in `host-checks.json`. No physical Fold6/Glasses tests or sensory measurements were run.

## Pending source and publication reconciliation

Final pre-publication remote refresh still returned accepted `b3c9aa95f344dd5be8601baa8efd2d4bec40dc02`; pending PR #79 remained **open/unmerged** at `4c4fc400f713cbff295cbd10280fd14e37939f22`. Independent semantic CHANGES_REQUESTED/R78-1 remains unresolved; a GitHub `COMMENTED` review must not be mistaken for acceptance. The candidate's failed ten-minute decoder interval and missing fresh-capture/already-blanked Awake case remain explicit.

Last verified donor runtime revision **353** identifies its existing Manager, waiting Worker, independent Reviewer and retained preview ownership. Coordination request `OWNER-EYEPROJECTOR-INIT-02-DONOR-COORD-01` was persisted and accepted through the verified Paseo explicit-steer SDK; no correlated recipient receipt was available at this audit snapshot. An intermediate bounded SSH ownership refresh timed out. A subsequent registry read succeeded on 2026-10-19 at about 09:34 UTC and reconfirmed revision 353 and the same declared custody/expected waiting state. A separate bounded Paseo log read failed with transport closure, so current controller lifecycle and recipient receipt are not inferred from that registry. No source/issue/device handoff occurred. The donor source and active work remain intact; accepted-baseline initialization does not require taking over their active issue or live session.

Independent review of initial commit `f1391b6130063a625220ddf088d88fe8db8489fe` [requested INIT-R1](https://github.com/code2hack/EyeProjector/pull/2#pullrequestreview-5569514361): the new runtime helper inherited remote daemon selection before assigning a local hostname. The correction validates the installed SDK-selected endpoint as local IPC/loopback and pins that exact endpoint before observing bindings or writing files; inherited remote endpoints fail closed. The regression, eight helper tests and 23 independent fixture checks passed on corrected helper SHA-256 `51a679b44ccb8993b62b133a702239fbb77909dabbac2076a542240fa3165732`; actual root read-only inspection passed with the selected local endpoint. [runtime-checks.json](runtime-checks.json) binds this evidence. The corrected exact head still requires renewed review. Android implementation and build inputs are unchanged. The Reviewer also assessed the automated decoder warning as an inherited product limitation rather than an additional migration correction.

The initialization [PR #2](https://github.com/code2hack/EyeProjector/pull/2) carries exact branch/head/base and the renewed independent verdict. The initial rejected review remains attributable to INIT-04/INIT-06; it is not reported as acceptance. `main` remains at the initial README until exact-head independent acceptance and protected-document Owner approval. Any later merge must refresh destination head, accepted/pending source, reviews and ownership again, retaining or incorporating only an actually accepted delta. The new repository's tracking issue is separate from historical closed investigations.

Remaining product work: physical acceptance of the renamed apps, full Phone-IME usability, panel-off continuation/fresh startup, decoder endurance, reboot bootstrap, audio isolation and target-app coverage. These are unresolved capabilities, not bootstrap build failures. A real Manager assignment/monitoring-host setup, coordinated pending-work handoff and Owner document publication are separate remaining gates.
