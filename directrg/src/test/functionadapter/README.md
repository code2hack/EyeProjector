# Function Activity and adapter host checks

```sh
python3 directrg/src/test/functionadapter/run.py --source-dir . --output-dir /absolute/new/evidence
```

The runner compiles complete production native/HID adapters and Function grammar. It extracts
both Function initialization blocks and the exact capture/context/dispatch methods from
ReceiverActivity, preserving their source hashes and compiler inputs. Surrounding Android,
UI, pointer and Session APIs are explicit host boundaries. Actual Session transport is checked
separately by FunctionIntentTransportTest and the Phone Function composition runner.

Cases cover native and HID repeated activations, duplicate native summaries, registration
release, original hover/Session capture, local context, replacement, pointer-callback retirement
and unavailable/unsized context. Function does not dispatch Back, Home or sleep.

The native report order/timing is modeled. This suite cannot establish actual firmware reports,
interception, device behavior, Anchor execution or physical usability.
