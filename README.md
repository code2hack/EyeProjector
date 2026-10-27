# EyeProjector

EyeProjector runs supported Android apps on a Phone-owned workspace and projects them directly to smart glasses, with head pointing, native/HID input, a Glasses-fed virtual camera and native Bluetooth audio.

**v0.2 is Owner-accepted.** The managed-display path supports the exercised locked/blank physical Phone configuration, ordinary-app Glasses camera previews, automatic Glasses microphone routing and projection-only native layout scaling. The accepted keyboard fix presents completed late redraws instead of leaving a stale picture.

## Start here

- [SPEC.md](SPEC.md): current behavior, controls and compatibility limits.
- [Managed-display setup](docs/versions/v0.2/managed-display.md): Shizuku/platform prerequisites and the current Glasses-local helper bootstrap.
- [v0.2 run record](docs/runs/v0.2.md): Owner acceptance and measured versus unmeasured scope.
- [v0.2 release](https://github.com/code2hack/EyeProjector/releases/tag/v0.2): published source and APKs.
- [RUNBOOK.md](RUNBOOK.md): engineering toolchain and baseline build/debug procedures.
- [AGENTS.md](AGENTS.md): agent roles, coordination and publication.

The build contains `directhost` (Phone), `directrg` (Glasses), `protocol` and `hostprobe` (controlled fixtures). The advanced managed/virtual-camera path requires supported Android framework APIs and an authorized backend; minimum SDK alone is not a compatibility guarantee. The native pad/power helper is manually bootstrapped, and the approximately three-second stock native hold limit remains accepted; HID supports longer holds.

Private identities, device addresses, credentials and development runtime records stay outside public source. Historical v0.1 behavior remains in [its run record](docs/runs/v0.1.md).
