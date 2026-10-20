# EP-02 — Pair and remember a LAN peer with the displayed code

**Canonical issue:** [#6](https://github.com/code2hack/EyeProjector/issues/6). **Blocked by:** [#5](https://github.com/code2hack/EyeProjector/issues/5), [#9](https://github.com/code2hack/EyeProjector/issues/9) for the composed Phone candidate. Feature-local work began independently.

**SPEC:** [Pairing and control panels](../../../../SPEC.md#pairing-and-control-panels), [execution and transport](../../../../SPEC.md#execution-and-transport).

Implement discovery and code-based peer establishment on shared Wi-Fi and the Phone hotspot. Glasses show a four-digit code with a five-minute countdown/rotation; the Phone's Pair action opens code entry. Successful pairing persists the authenticated peer identity used by the existing transport.

**Primary writes:** pairing/discovery components and private peer storage in both apps, `Tls.kt` integration, small panel hooks and pairing tests. EP-03 retains ownership of media framing.

**Acceptance**

- Code entry pairs the intended devices; expired/wrong codes and an unexpected peer do not establish trusted projection.
- The code rotates after five minutes, and panels reflect paired/unpaired state.
- The Owner-approved limit permits five attempts per code, then waits for rotation; rotation restores admission and existing projection is unaffected.
- Remembered identity survives app restart and resolves the peer's current LAN endpoint without requiring code re-entry.
- Forget removes EyeProjector trust and returns the initiating panel to unpaired state; native Bluetooth pairing remains under Android management.
- The pairing design explains how the displayed code authenticates the exchanged identity, rather than merely exchanging an unauthenticated certificate.

**Verification:** deterministic expiry/rotation and peer-establishment tests, affected builds, and physical pairing on both specified LAN topologies. Connection/session behavior after pairing belongs to EP-04.
