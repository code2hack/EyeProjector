# EyeProjector

EyeProjector projects supported Android applications from a Phone-hosted virtual display onto smart glasses, with direct authenticated video/audio transport and glasses-native input.

## Project documents

- [SPEC.md](SPEC.md): product goals, behavior and acceptance.
- [AGENTS.md](AGENTS.md): multi-agent roles, orchestration, communication and review.
- [RUNBOOK.md](RUNBOOK.md): toolchains, builds, lint and device debugging.
- [Manager runtime skill](.agents/skills/manager-runtime/SKILL.md): initialize the local participant registry and register the assigned Manager with the installed watchdog.
- [Initialization audit](docs/migration/initialization-audit.md): imported source, renamed identities, verification and remaining handoffs.

The build contains `directhost` (Phone host), `directrg` (Glasses receiver), `protocol` (transport/backend parsing), and `hostprobe` (controlled fixtures). Build and test commands are in the runbook.

This repository bootstrap is not a product release. Physical acceptance of the independently named applications remains separate from host builds and historical observations. Live participant identities, device addresses and credentials stay in local configuration.
