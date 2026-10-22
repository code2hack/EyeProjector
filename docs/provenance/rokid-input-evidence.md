# Rokid native input evidence

Research for the v0.1 interaction discussion, 2026-10-20. This note records historical source and reported physical results; it does not establish current EyeProjector device acceptance or assign a new gesture map.

**Later physical update:** the Owner-authorized [2026-10-20 trial](rokid-long-touch-2026-10-20.md) demonstrated foreground one-finger long-touch AI suppression on firmware `1.26.011` with the historical interceptor. The historical results below retain their original boundaries; EyeProjector integration remains pending.

## Device and source identity

The latest retained projection record identifies serial `1906092617103125`, model `RG-glasses`, Android 12/API32, fingerprint `Rokid/glasses/glasses:12/SKQ1.240613.001/1.26.011-20260929-150201:user/release-keys`. This is a retained observation, not a fresh device query. The current native input adapter identifies the pad as `ROKID,PSOC-TP-R`. [Device record](pending-panel-power-results.md), [input adapter](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt)

Remote `code2hack/Glasseo` main was refreshed to `5f9d23512359aaaef7d5e1ba203ea7322cae433b`; the local clean main matched. The accepted input work is [PR #25](https://github.com/code2hack/Glasseo/pull/25), head `275eae875685795a632d167b842b03ba089862db`, merge `03ec827bc7dc37958469a3840d1cc7e29f882f60`. Historical identifiers below identify that source and its evidence.

## Matching official documentation

Rokid's consumer [bare-metal introduction](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=ede9035b1e494da197703b239defb4c3) describes YodaOS-Sprite, Android applications and a 480×640 display. This matches the retained device family more closely than Glass3 or Sprite Enterprise guides. Rokid's [model table](https://global.rokid.com/en-jp/pages/security-center) lists consumer Glasses as RV101/RV102 separately from Glass3 RG301/RG303; the retained fingerprint does not resolve RV101 versus RV102.

The consumer [input chapter](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=ca8fedf26d534e1fabb8a34d1fa24e98), version 1.2, created 2026-08-26, documents dynamic exported receivers, high priority and ordered-broadcast cancellation alongside Activity key consumption. Its [public chapter payload](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/ca8fedf26d534e1fabb8a34d1fa24e98) was read directly because the page is an SPA. It says API31, while the retained device reports API32, and supplies no exact firmware compatibility statement.

Documented candidates include the function button's click/down/up/double/long/very-long broadcasts, single-finger long press, two-finger double-tap, two-finger swipes and two-finger long press. Tap/double-tap use ENTER/BACK; one-finger swipes use RIGHT→DOWN or LEFT→UP sequences within 500 ms. These are documented channels, not current-device suppression results. [Input chapter](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=ca8fedf26d534e1fabb8a34d1fa24e98)

Two discrepancies need device evidence: the chapter calls two-finger double-tap `ACTION_TWO_FINGER_DOUBLE_TAP`, whereas historical observed secondary handling included `ACTION_KEYCODE_BACK`; it also describes two-finger single-tap as NOTIFICATION83 but ends by calling it unsupported. EyeProjector currently treats NOTIFICATION83 as contact input for tap recognition. Replacing that behavior from documentation alone would be unjustified. [Official chapter](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/ca8fedf26d534e1fabb8a34d1fa24e98), [historical broadcast evidence](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5507517004), [current recognizer](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/PadGestureRecognizer.kt)

## Two delivery paths

Foreground Android `KeyEvent` consumption and YodaOS system broadcasts are separate channels. Glasseo implements both [Activity input consumption](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/MainActivity.kt#L241) and [broadcast interception](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/BroadcastInterception.kt#L55). Consuming an Activity key does not establish suppression of a separately executed system action.

The broadcast receiver uses a dynamic registration with priority `Int.MAX_VALUE`. For a known action, it checks `isOrderedBroadcast` and absence of `FLAG_RECEIVER_NO_ABORT`, then sets `RESULT_CANCELED` and calls `abortBroadcast()`. Android documents that abort only affects ordered broadcasts, and the sender's final result receiver can still run. An abort attempt therefore needs a separate observation of the resulting system behavior. [Exact implementation](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/BroadcastInterception.kt#L36), [Android API](https://developer.android.com/reference/android/content/BroadcastReceiver#abortBroadcast())

| Full action prefixed by `com.android.action.` | Retained result |
| --- | --- |
| `ACTION_SPRITE_BUTTON_UP` | Physical short function-button presses: ordered delivery and suppression accepted. |
| `ACTION_SPRITE_BUTTON_LONG_PRESS` | Physical long function-button presses: ordered delivery and suppression accepted. |
| `ACTION_AI_START` | Included in the persistent interception list; complete safe long-touch behavior remains unaccepted. |
| `ACTION_SETTINGS_KEY` | Dynamic receipt observed, `ordered=false`, no abort attempted; AssistServer also handled the action. |
| `ACTION_KEYCODE_BACK` | Dynamic receipt observed, `ordered=false`, no abort attempted; AssistServer also ran its TTS/back path. This was the two-finger control, not proof about the projection receiver's one-finger double tap. |
| `ACTION_TWO_FINGER_SWIPE_BACK`, `ACTION_TWO_FINGER_SWIPE_FORWARD` | Qualification observation exists in code; no accepted safe complete control result. |

Sources: [physical COMMAND report](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5505664971), [broadcast evidence index](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5507517004), [qualification action mapping](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/MainActivity.kt#L655).

The observed function-button and nonordered actions both carried flags `0x50000010`. Eligibility for registered-only delivery is distinct from ordered delivery and abortability. [Evidence index](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5507517004)

## Accepted historical controls

| Physical control | Historical disposition |
| --- | --- |
| Function button, short and long | `AVAILABLE_WITH_SUPPRESSION`. |
| One-finger backward/forward swipe | `UP`/`DOWN`, accepted as `AVAILABLE_SAFE`. |
| One-finger touch/tap | Short PRIMARY observable; the combined PRIMARY control also required long behavior, which was not accepted. |
| Long/double two-finger touch | SECONDARY unavailable in the accepted matrix. |
| Two-finger backward/forward swipe | LEFT/RIGHT unavailable in the accepted matrix. |

The Owner ended further built-in qualification and accepted this bounded matrix. “Unavailable” describes that investigation's accepted result, not a proof that future firmware or another mechanism can never expose the gesture. Observable single taps failing a combined short/long qualification do not contradict the narrower tap behavior in the projection receiver. [Owner disposition](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5507465333), [final report](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5511625510)

The strongest function-button physical record contains two short and two long presses during a 90-second window. Android policy consumed the raw `KEY_MENU` path; the app received ordered broadcasts instead of Activity key callbacks. All four operations recorded no duplicate semantic event, no AssistServer handling, no camera/recording activity, and stable foreground, PID and brightness. All occurred in their matching qualification steps. Physical background/outside-step behavior was not demonstrated by that run. [Worker report](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5505664971), [Manager assessment](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5505665127)

### One-finger long touch: observed failure followed by an unfinished retest

The retained Manager session `01a05d84-0a79-7cd3-b399-4f4799104887` contains the original Worker handback at `2026-09-01T21:48:43.164Z` (rollout line 2763). It reports a physical long-touch capture in window `463565238622229–463625238622229`: the Activity consumed the initial NOTIFICATION contact, but the held KEYCODE_PROG_BLUE path was handled by Android policy. Rokid AI displayed its assistant dialog, played a wake sound, started CXR audio capture and took focus for about 9.85 seconds. The ordered ACTION_AI_START record showed `resultAbort=false` and only AssistServer delivery. This was an actual failure of Activity key consumption to stop AI, before the added dynamic broadcast interception.

The same session's later handbacks at `2026-09-02T04:59:22.709Z` (line 5145) and `05:01:43.363Z` (line 5212) record persistent-interceptor retests as NO_INPUT/UNCLASSIFIED and OWNER_INTERRUPTED_UNCLASSIFIED. They supply no classified physical result for the added long-touch interception. The accurate distinction is therefore: initial AI conflict observed; outcome after the interception change unverified. There is no established finding here that AI still appeared after Glasseo successfully aborted that broadcast. These are retained Worker reports; original raw capture bytes remain unavailable.

## Lifetime and evidence limits

An earlier step-scoped interceptor was removed when qualification advanced; a subsequent long function-button press reached AssistServer, force-stopped the app and triggered video. The correction registers the receiver once from `Application.onCreate` and retains it for the process lifetime, including periods without a wizard observer. The code has no explicit release operation. This solves the reported registration gap but is broader than a foreground interaction scope; controlled exit/restoration remains a separate behavior to design and verify for EyeProjector. [Earlier diagnosis](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5504501531), [application registration](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/GlasseoApplication.kt#L21), [review observation](https://github.com/code2hack/Glasseo/pull/25#discussion_r3915581027)

The public COMMAND report does not pin its firmware fingerprint. The final complete seven-control physical run used an external Joy-Con on firmware `1.25.012-20260901-150201`; it is not a complete built-in-touchpad result and does not qualify the retained `1.26.011` device build. [Final report](https://github.com/code2hack/Glasseo/issues/3#issuecomment-5511625510)

Referenced raw directories were absent during this research, so their bytes and recorded checksums were not independently verified. This note distinguishes source inspection and accepted historical reports from newly executed physical measurements.

## EyeProjector implication

The current receiver consumes native pad keys in `dispatchKeyEvent` and maps contacts, tap/double summaries and directional reports. It has no Rokid broadcast receiver, so it cannot currently reclaim the function button through the historically proven channel. [Current receiver](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt)

Recommended direction for the interaction design: retain the existing proven tap/scroll behavior, add the function-button channel using the accepted ordered-interception mechanism, and qualify additional gestures individually against both app delivery and visible system effects on the actual firmware. Define interception lifetime and restoration alongside the gesture mapping. This is a design recommendation, not an implementation or a claim that all global actions can be cancelled.

The initial research was read-only. The later Owner-authorized physical test and its device changes are recorded in the linked trial report. Historical checkouts and runtime registrations were unchanged.
