# HID admission and held-route host checks

Run the actual HID/held ingress extracted from the supplied ReceiverActivity,
with the production HID recognizer, owner, held dispatcher and local dispatcher:

```sh
JAVA_HOME=/path/to/jdk17 python3 directrg/src/test/hidadapter/run-held-local.py \
  --source-dir /path/to/repository \
  --source-activity /path/to/repository/directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt \
  --output-dir /path/to/a/new/evidence-directory
```

The replacement regression starts Tap DOWN1000, replaces it with DOWN1100, and
admits DOWN1200 from the old target's synchronous CANCEL callback. The newest
press must retain its real UP1200; the outer replacement cannot publish after
it. Related scenarios cover nested initial/replacement pointer publication and
the actual LocalPointerDispatcher deferred-CANCEL stack. The dedicated JVM
`HidAdmissionReentrancyTest` also covers a completed nested press, cancellation,
context replacement, exact pending-release retirement, unrelated releases and
an independent Function press.

The remediation's parent RED and corrected GREEN runs use identical fixtures,
compiler dependencies and unchanged Receiver/native/local output source. Input
snapshots and logs distinguish the expected parent failures from the corrected
runs. Android/session/target boundaries are explicit host doubles; these checks
do not establish device delivery, physical HID identity, head-pointer usability,
firmware/default suppression or full issue acceptance.
