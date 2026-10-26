# Actual notification diagnostic

`ChosenAppsInstrumentation` has one `notification-probe` operation. Its AndroidTest helper uses
the actual application context and production `PhoneNotificationRuntime` observer. It never
calls an NLS callback, activation/launch facade, root helper, permission-grant API or canonical
choice/preference mutation. Do not count the cached host boundary checks as Android evidence.

The operation returns `DIAGNOSTIC_COMPLETE` or `DIAGNOSTIC_ERROR`, with an independent
`notification.outcome`. Structured diagnostic errors finish with `RESULT_CANCELED`; completed
diagnostics finish with `RESULT_OK`, including explicitly reported unavailable prerequisites.
Completion, readiness, an ordinary `notify()` return and receipt of a
callback are different observations. None is a feature PASS. The allocated main observer captures
original receipt/G/D/arm/Ready/carrier and actual matching submission settlement. Correlation uses
those original references, never newest state or unrelated package/time/launch-log inference.
Missing stages remain `UNOBSERVED`. A typed committed settlement and a matching typed foreground
result are separate observations; neither establishes physical Glasses wake/display.

Inputs are `operation=notification-probe`, a public synthetic `runId` of 1–48 letters, digits,
`_` or `-`, `notificationMode`, optional exact `postingPackage` (defaults to target host package),
and `waitMs` (1000–60000, default 20000). `cleanup-owned` also requires the exact retained public
`fixtureToken` from the original invocation intent. The finite observation window bounds only this test;
it adds no product expiry, rate limit, retry loop or policy. A new invocation UUID owns its
channel/tag/id even if a caller reuses `runId`.

- `status`: reads actual listener approval, callback connection, returned-baseline observation,
  target posting permission/enabled status, and only the requested package's chosen/On flags.
  It does not post or wait for an event. Approval is separate from connection. `OBSERVED`
  baseline does not establish complete/successful underlying Android RPC. Pipeline subscription
  itself performs no replay or activation.
- `observe-event`: waits for readiness, then emits the intermediate
  `AWAITING_ORDINARY_EXTERNAL_APP_EVENT` status. Root causes one ordinary notification in the
  named real app. The actual NLS observation identifies that package, with only
  `PACKAGE_CALLBACK_ONLY` correlation. It cannot identify one specific external message or
  establish semantic novelty. Permission fields describe the target app only, explicitly
  leaving an external app's posting permission unobserved.
- `post-owned`: requires the actual target host posting package, chosen membership and ordinary
  posting permission/enabled state. It creates a new UUID channel and posts one notification
  through the target app's `NotificationManager`, with a required stock small icon and no
  title/body/image/content intent. It observes the exact owned tag/id through the public
  app-owned notification API and compares the returned opaque key/postTime to the real NLS
  observation **only in memory**. `EXACT_OWNED_PLATFORM_KEY_AND_VERSION` is record correlation,
  not a semantically new message or native-incarnation guarantee. Off is reported as
  ineligible but does not suppress ingress observation. Unchosen packages require setup
  because the production runtime intentionally does not extract their records.
  Its invocation-owned synthetic channel uses `IMPORTANCE_LOW` to avoid an audible fixture
  alert under ordinary Android channel behavior. Production notification policy and Owner
  channel/audio settings are unchanged; actual device quiet behavior is separately observed.
- `cleanup-owned`: after Root has reconciled an uncertain or terminated original invocation,
  accepts its retained canonical UUID and the exact target posting package. It derives only
  that fixture's fixed channel/tag/id; no arbitrary channel/tag/key/id is accepted. It performs
  no runtime/choice/preference/grant reads or automatic retry. It reports cancel/delete API
  returns, leaving original invocation settlement and platform resource settlement unproven.

Only requested-package metadata is retained for the finite run. Keys, tags, message payloads,
notification objects, arbitrary errors and unrelated chosen apps are never rendered. Connection,
membership, epoch and receipt get invocation-local identity labels; they do not identify native
origin or reconstruct history after a process restart. Classification can be baseline-relative
unknown, a duplicate/replay, or a distinct repost including delayed historical/routine delivery.
No content inspection, ongoing/update filter, cap or expiry is introduced.

External-event observation is armed after readiness, before its intermediate prompt; callbacks
seen during setup cannot satisfy that window. After a correlated receipt, the helper emits
`RECEIPT_OBSERVED_AWAITING_ORIGINAL_PIPELINE` and retains the ordinary notification/session for the
remainder of the **same** finite window. It does not immediately cancel asynchronous selection
work on receipt. Root can separately observe Receiver/wake/foreground during that interval.
The remaining window is reported; reaching its end does not establish pipeline completion.
After the window, the helper reports only pipeline events whose original ingress object is
identical to the matched real receipt. It labels original G/D/R/Ready/H/A/C/S locally without
printing platform or authority tokens. Settlement rows keep commitment, rejection, fault stage,
null-result state, typed launch status and original package/context/foreground match flags
separate. Receipt-derived fields and every dynamic settlement row are rebuilt on each refresh;
an inconsistent chain or a different receipt with no pipeline data cannot retain an earlier
claim, foreground match or G/D/carrier/result row. Run/setup facts and local label identity remain
separate. `COMMITTED_FROM_MATCHING_SETTLEMENT` requires that actual S invocation return and its
matching lane-settled receipt. A matching typed foreground result remains distinct from a
physical observation. A different receipt or inconsistent G/D/carrier cannot supply success.

The main observer captures original metadata outside policy/fast locks and the final claim/Binder
interval, then delivers through the existing main-post function. There is no registration replay.
Closing suppresses queued delivery, without acting as a barrier for an already executing observer.
Observer/scheduling faults are isolated from primary product operations. Submission metadata is
captured immediately before invocation but dispatched after invocation returns, preventing an
inline observer from installing a newer manual choice before an older submit reaches its queue.
Settlement is emitted once per original S, including retired-owner late outcomes. Stage lists
show observation delivery order; inline posting can nest fanout, so they are not a universal
source clock. Captured permit state is an observation, not a current authority check. An exceptional
adapter's unresolved lifetime does not establish global physical nonoverlap.

The helper closes all three original observers independently, cancels only its exact attempted notification
tag/id and deletes only its newly owned UUID channel in `finally`, including posting failures.
Before resource creation it emits `OWNED_FIXTURE_INTENT` with a public synthetic `fixtureToken`.
The fixed channel/tag is `eyeprojector_probe_` plus that token and notification id is 1. This
is the run's public ownership intent, not an extracted platform key or private app tag. Root can
retain it for separately authorized exact recovery if process death prevents `finally`.
It reports API-return cleanup evidence and cleanup faults; API return is not proof that every
platform effect has settled. It never cancels all notifications or deletes a reused channel.
Primary diagnostic outcome/result is retained separately from final report refresh and each
of the three original observer-close outcomes. A refresh or one close failure cannot skip another
close; thrown cleanup leaves that leg/settlement unknown. Chosen membership, notification
preference and permission remain unchanged by the helper.

`Instrumentation.finish()` exits the instrumented app process. Results and exact owned cleanup
must complete before finish; it does not preserve the previous listener/G/session. Root must
reserve/reconcile the app/session around the diagnostic. A fresh process baseline does not
reconstruct previously uncommitted G/history. Cleanup or callback suppression cannot undo an
already committed launch. Receiver foreground, native continuity, global Home and physical
Glasses wake/display require separate observations.

## Passive original-receipt trace219

The existing pipeline subscription alone enables an optional `NotificationObservationTrace`
on the original receipt and its original admission ticket. There is no fourth subscription,
replay, new authority query or diagnostic admission path. The probe compiles the actual production
trace DTO; its Android/main observation boundaries remain explicit doubles in cached helper checks.
The pipeline suite separately compiles the actual owner/policy/runtime and their existing facade.

`traceCorrelation` distinguishes an exact original receipt, absent trace, mismatched original and
trace-rendering fault. The helper copies `snapshot(receipt.original)` on main outside product locks.
`acceptDisposition`, `prepareDisposition`, `installDisposition`, fault families and the seven
`traceCheck.<name>` fields report captured facts. `NOT_EVALUATED` preserves short-circuit behavior;
an unobserved install is not reconstructed from a null/non-null facade return or later chosen/On
read. `admissionInstalledG` and `admissionReturnedHandle` label only actually captured objects.
The latter is an opaque returned handle, not a new Ready/goal/claim assertion. These trace fields
never populate the independent pipeline G/D/Ready/claim fields. Changing the selected receipt
clears all old trace and pipeline-derived proof before recomputation.

`producerCaptured`, `enqueueAttempted`, `enqueueReturned`, enqueue faults, callback entry/return/
faults and inactive-skip counts describe their respective cuts. `enqueueSemantics` explicitly says
`POST_UNIT_RETURN_NOT_QUEUE_ACCEPTANCE`: a returned Unit post is not evidence that Android accepted
or delivered a queued message. No counter becomes a retry or admission signal.

The helper records callback entry before the first ingress getter. Its run-scoped entry and
uncorrelated-fault fields do not identify an original receipt; early faults never borrow the
latest receipt. Once the same-original box is known, `helperEntered`, `helperAppended`,
`helperFaults`, `helperFaultFamily` and `helperFaultPoint` distinguish entry, normal append return
and detailed-row faults. `BEFORE_APPEND` means row construction failed before invoking append.
`APPEND_UNCONFIRMED` means append was invoked but did not return; a partial effect is not ruled out.
An append counter and a fault can also describe different callbacks for the same original N.
The identical primary Throwable is rethrown to the producer's existing isolation; recording a
diagnostic fault cannot replace it. No raw error message, key, ticket or authority token is exported.
An entire trace-rendering failure is isolated from independent pipeline/result derivation.

Source219 is passive observation, not a functional repair or an established explanation for
actual198. Previously installed main75 predates this trace and cannot supply these new facts.
Any later actual event requires its separately allocated reviewed main/test build and device route.

## Finite Root procedure, after separate build/device allocation

1. Pin the reviewed source and newly built Phone main/test APK hashes. Root reserves both
   affected TCP5555 primary routes and observes actual Phone/Glasses identity. Glasses35117
   is bootstrap-only. Install only under Root's precise grant, preserving Owner state.
2. Observe existing ordinary listener approval; do not shell-enable it. For self-posting on
   API33+, the source133 debug-only `POST_NOTIFICATIONS` declaration and an ordinary visible
   permission grant are necessary. The declaration does not grant permission or open a dialog.
   Root's separately allocated ordinary setup must observe the grant. No new Activity/service is
   needed. Root must observe actual
   app/channel enabled status. Do not use shell-post, hidden grant or keyguard bypass.
3. Prefer an already chosen package and existing On state. Self-post uses
   `com.code2hack.eyeprojector.directhost`, never the test APK/shell package. If Root separately
   authorizes temporary canonical setup, record whether this exact package was already chosen
   and whether its preference was absent/default-On or explicitly saved. Add/remove only the
   fixture package through ordinary canonical UI, preserving others/order. Do not write an
   absent default-On preference. Restore any deliberately changed explicit value. The helper
   performs none of these mutations. Keep permission disposition Root-owned.
4. One bounded status command, using Root's reserved exact Phone route:

   ```sh
   timeout --foreground 90s adb -s PHONE_TCP5555 shell am instrument -w -r \
     -e operation notification-probe -e notificationMode status \
     -e runId n65_status_01 \
     com.code2hack.eyeprojector.directhost.test/com.code2hack.eyeprojector.directhost.ChosenAppsInstrumentation
   ```

   Retain the full structured diagnostic output and exit. This run exits its app process;
   reconcile/re-establish the actual session before later event runs.
5. After actual setup, one event command changing mode to `post-owned`, a distinct run ID and
   `-e waitMs 20000`. Or use `observe-event -e postingPackage EXACT_CHOSEN_APP`, wait for the
   intermediate ready status, then trigger one ordinary event in that exact app. Do not substitute
   manual callback/direct launch. Retain status, event origin/kind/baseline and correlation
   strength separately from any Root-observed Receiver/foreground/wake result. Missing setup,
   no receipt, missing original pipeline stage or failed/unknown settlement is a measured limit,
   not a PASS. Keep result-context/package/foreground matches distinct from physical evidence.
6. Retain exact cleanup-return/fault fields. Finish exits the app; Root reconciles both app/session
   state and separately authorized setup cleanup. If the process timed out/crashed, reconcile that
   original invocation before separately authorizing one `cleanup-owned` run using its retained
   `fixtureToken`, a new cleanup `runId`, and the exact target package. This derived-identity
   cleanup makes no delivery/original-settlement claim. No automatic instrumentation rerun,
   reinstall, broad log/private-state dump or permission mutation is implied by a failed stage.

The actual affected host request is `:directhost:testDebugUnitTest :directhost:assembleDebug
:directhost:assembleDebugAndroidTest :directhost:lintDebug` under a new exact shared JDK17/SDK35
host slot, with Root-supplied bounded argv/environment. Both affected main/test APKs must be
regenerated and pinned with actual task/output reuse recorded. No SDK/Gradle/device execution
is authorized by this README.

## Cached source checks

`python3 directhost/src/test/notificationprobe/run.py --output-dir NEW_PRIVATE_DIRECTORY`
compiles the actual AndroidTest helper/report against explicit platform and observation-only
main boundaries, not an Android SDK. Each unique closed run retains exact input snapshots,
dependency/JDK/argv hashes, modes, logs and timeout/exit information. Its synthetic callback
scenarios establish probe control flow, not actual NLS delivery, G/claim authority or Android
permission behavior. The separate `pipeline/run.py` check compiles the actual current owner and
its production source closure for original-observer/queue ownership checks, with documented
platform/native/Binder boundaries. Results from overlapping suites are not summed.

References: [NotificationManager app-owned metadata](https://developer.android.com/reference/android/app/NotificationManager#getActiveNotifications()),
[ordinary posting permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission),
[listener callback readiness](https://developer.android.com/reference/android/service/notification/NotificationListenerService#onListenerConnected()),
[instrumentation lifetime](https://developer.android.com/reference/android/app/Instrumentation#finish(int,%20android.os.Bundle)).
