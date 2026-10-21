# Function adapter and contextual navigation host scenarios

With the pinned Kotlin compiler already in the Gradle cache and JDK 17:

```sh
JAVA_HOME=/path/to/jdk17 python3 directrg/src/test/functionadapter/run.py \
  --source-dir /path/to/repository \
  --output-dir /path/to/private/evidence
```

This suite compiles the actual `NativeFunctionInput`, `FunctionGestureRecognizer`,
`FunctionNavigation` and `SessionPresentation`. It extracts the exact Function
initialization and contextual navigation, cancellation, disconnect and lifecycle
method bodies from the supplied `ReceiverActivity`. Android registration, scheduling,
session, decoder and Surface boundaries are small observable doubles. No substitute
navigation `when` is supplied by the tests. The full Activity and Android framework
are not executed. `source-manifest.json` binds the Activity, extracted blocks,
generated wiring, production, test and compiler bytes; `result.json` records both
bounded command exits and artifact hashes. Compilation is bounded to 60 seconds,
scenario execution to 15 seconds. Each attempt uses a separate evidence directory.

The scenarios cover API32/33 receiver registration, idempotent release, ordered
abort/result cancellation, unordered/nonabortable delivery, unrelated AI/pad reports,
queued delivery after unregister, stale scheduled callbacks, contextual Home/Back,
panel entry and return, local main-panel navigation, long no-op, explicit launcher
intent, exit peer-callback retirement, and focus/pause/destroy/disconnect cancellation.
Host session/decoder/Surface identity checks refer to boundary objects only. The
updated `ConnectedPanelChecks` instrumentation exercises the real adapter callback
and real Android session/decoder/Surface identities on a later reserved device run;
it does not emit system Function broadcasts or establish firmware suppression.

The default **500ms quiet / 800ms long** values are injectable gesture models.
Completed DOWN/UP edges count presses; CLICK/DOUBLE/TRIPLE summaries raise the same
sequence's classification and close a missing UP. Repeated held DOWN and duplicate
UP/CLICK do not add presses. Final dispatch retires ownership before navigation.
Cancellation retains summary duplicate ownership but permits fresh DOWN immediately,
including after a lost UP while unfocused. A held long press consumes further DOWN
repeats until release. Summary-only repeats have no identity: within the retirement
interval a new independent summary cannot be distinguished from a late duplicate.
Unit tests expose that boundary, and show that a LONG arriving after a finalized
CLICK cannot undo it. UP-before-DOWN ordering and stale summaries interleaved with
fresh edges also require real firmware evidence; no arbitrary-delay claim is made.

Host results do not prove actual firmware report timing/order, once-only physical
Function gestures, Phone navigation targets, Android launcher destination, competing
Rokid AI/pairing suppression, or optical behavior. Unordered/nonabortable delivery
can classify input but supplies no system-suppression proof. Those core physical
acceptance gates remain open.
