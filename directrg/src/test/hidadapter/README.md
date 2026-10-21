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

R3 extends those controls across transient attachments and different admitted
keys that replace the same output contact. The exact attachment7 DOWN1000,
outer7 DOWN1100, CANCEL callback attachment8 DOWN1200 sequence must retain
only DOWN1200 and its real UP; an older outer DOWN cannot resume even if the
nested press completes or is explicitly canceled before the callback returns.
Function keeps its independent admission and timing. Matching pending terminals,
foreign/stale releases, pointer publication and the Receiver's existing owner
check after native cancellation retain their separate ownership assertions.

The full ingress main retains every original case, including all four earlier
remediation cases omitted from the narrower independent R3 probe. Named cases
report individually and aggregate failures. Additive host fixture capture records
the actual local MotionEvent downTime/eventTime and projected callback times;
it does not substitute synthetic press identity for Android timestamps.

The remediation's parent RED and corrected GREEN runs use identical fixtures,
compiler dependencies and unchanged Receiver/native/local output source. Input
snapshots and logs distinguish the expected parent failures from the corrected
runs. Android/session/target boundaries are explicit host doubles; these checks
do not establish device delivery, physical HID identity, head-pointer usability,
firmware/default suppression or full issue acceptance.
