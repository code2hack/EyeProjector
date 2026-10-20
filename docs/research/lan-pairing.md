# LAN pairing implementation

Worker: Worker-EyeProjector-6 — issue #6.

The Glasses display a random four-digit code for 300 seconds of monotonic elapsed
time. Expiry rotates the code and invalidates an exchange that has not completed.
The Phone opens a code prompt and lists nearby displays by the same short identity
label shown on the Glasses. Selecting a display binds pairing to that discovered
certificate fingerprint. The full fingerprint is retained and verified internally.

The pairing TCP service is separate from projection framing. It uses Bouncy Castle
1.83's J-PAKE implementation with the NIST 3072-bit group and SHA-256. Both sides
validate the zero-knowledge proofs and the mandatory round-three key confirmation.
The code is not transmitted. A recorded exchange does not provide the ordinary
offline code-testing opportunity of a code-derived HMAC over public certificates.
An active participant can still guess a code online; four digits provide 10,000
possibilities. The displayed code is a temporary authorization secret.

The Owner-approved attempt policy allows at most five failed or in-progress
handshakes per displayed code. Each accepted pairing attempt reserves a slot
before the PAKE exchange; failure retains that used slot. A successful exchange
releases its in-progress slot and the panel changes to paired. When the budget is
exhausted, the Glasses show an explicit wait message and the remaining countdown.
Code rotation resets the budget; late completions from the old code cannot alter
the new budget. This policy affects pairing admission, not an existing projection.

After key confirmation, HKDF-SHA256 derives separate Phone-to-Glasses and
Glasses-to-Phone AES-256-GCM keys. The KDF and authenticated data bind the protocol
version and fresh role-specific participant identifiers. Those identifiers include
the full local certificate fingerprint and a fresh UUID. Each encrypted identity
record contains the role, certificate, pairing/projection ports and a signature
over the record plus this exchange binding. The receiver checks certificate
validity, the expected role/fingerprint, stable TLS DNS name,
and possession of the corresponding private key. An encrypted confirmation precedes
trust persistence on the Glasses; its encrypted acknowledgment precedes Phone
persistence. An interrupted final acknowledgment can leave only the Glasses paired;
retrying the same code with that same Phone can complete the exchange, or Forget
on the Glasses allows a fresh pairing. A different Phone cannot replace a paired
identity using a code.

Private `files/session/` contains the local EC identity and PKCS12 password and an
atomic `paired-peer.properties` record with the peer certificate, fingerprint and
last endpoint. Local identities are generated in the app, without a computer or
system trust-store changes. Existing explicitly provisioned PKCS12 identities
remain readable. Neither Android backup nor native Bluetooth pairing is used for
EyeProjector trust. Forget deletes both the new peer record and legacy `peer.crt`,
clears the transport endpoint, and retains the local identity. In-progress pairing
cannot undo Forget. Stop and Disconnect do not delete trust.

Discovery uses UDP port 27184, directed broadcasts for each active IPv4 interface,
and limited broadcast. The listener responds to discovery queries and announces
its role, certificate fingerprint, ephemeral pairing TCP port and projection port
27183 every two seconds. Packet source supplies the current address. Advertisements
contain neither pairing codes nor certificates. Discovery hints can move an
already remembered peer to a new address but cannot create or replace its trust.
The Receiver uses that address and the remembered certificate's stable DNS SAN
for HTTPS name validation over mutually authenticated TLS. A peer at a spoofed
address cannot complete the exact-certificate-pinned handshake. Both clients and
servers must check the current pin after handshake, including resumed TLS sessions.
The host bootstrap must copy the current paired-peer record and endpoint when it
uses a separate private directory; it must stop that host before Forget removes
trust, so an old copy cannot admit new sessions.

UDP discovery needs an IPv4 LAN that permits peer traffic/broadcasts. Client
isolation, VPN routing and broadcast filtering may prevent discovery; the panel
directs users to check shared Wi-Fi or the Phone hotspot. Host JVM tests cover
cryptographic establishment, expiry, persistence, moving endpoint hints and TLS
admission. They do not establish Android PKCS12 compatibility, broadcast delivery
on physical devices, or either required LAN topology. Those require separately
recorded Phone/Glasses device evidence.

## Isolated Android checks

`protocol:assembleDebugAndroidTest` builds a standalone library test APK with
package `com.code2hack.eyeprojector.protocol.test`. Its runner uses only that
package's `files/pairing-instrumentation/` sandbox. It does not read or delete
either production application's peer store. Within a recorded device window,
install it and run the bounded Android container/crypto check on each device:

```sh
timeout 120s adb -s "$DEVICE" install -r -t protocol/build/outputs/apk/androidTest/debug/protocol-debug-androidTest.apk
timeout 120s adb -s "$DEVICE" shell am instrument -w \
  -e mode loopback \
  com.code2hack.eyeprojector.protocol.test/com.code2hack.eyeprojector.protocol.PairingInstrumentation
```

For a physical LAN test, start the Glasses runner with `-e mode lan -e role GLASSES`.
It reports the current `code` and `display` label and waits at most 90 seconds for
a Phone. On the Phone, run the same instrumentation with `-e mode lan -e role PHONE
-e code <displayed-code> -e display <displayed-label>`. Capture both results and
the actual network topology. Repeat on shared Wi-Fi and on the Phone hotspot.
This checks device crypto, discovery and persistence in isolated test state;
production-panel code entry, actual countdown/rotation, restart and Forget still
require the two application UIs and separate evidence.

Protocol references: [RFC 8236](https://www.rfc-editor.org/rfc/rfc8236),
[Bouncy Castle JPAKEParticipant source at 1.83](https://github.com/bcgit/bc-java/blob/r1rv83/core/src/main/java/org/bouncycastle/crypto/agreement/jpake/JPAKEParticipant.java),
and [HKDF, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869).
