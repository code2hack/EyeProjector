# HID joins and navigation destinations on the host

```sh
python3 directrg/src/test/hidnavigation/run.py --output-dir /absolute/new/evidence/directory
```

This cached-compiler suite joins actual production Phone controller/route/merger and
Glasses Android HID input/recognizer sources with the exact ReceiverActivity HID
constructor, completed-input initializer and dedicated navigation/cancellation methods.
The Glasses projected route also executes the exact ProjectionSession `navigate` and
`input` bodies with the actual ordered dispatcher and wire codecs. Inputs, extracted
bodies, commands, dependency hashes and results are retained per attempt.

The eight scenarios check projected Back/contextual Home, settings Home then explicit
app exit, Lock cancellation/sleep submission with retained modeled session, original
context retirement, Phone completed Home/Lock into the actual destination methods, and
controller cancellation after UP. Dedicated input runs with unavailable/off-screen
spatial position; Glasses navigation must never query that position.

Android widgets, HID device facts, main-thread scheduling, surrounding Activity state,
session/decoder lifetime and the existing sleep submission are explicit host boundaries.
Function capture is an unused boundary here and has separate actual-source regression
coverage. Phone stamped admission is modeled here; the final joined Session suite must
exercise the actual context-only accessor and stamped action branch. This suite does
not compile ShellMain, use an SDK/Gradle/device, perform a real network exchange, or
establish Android transitions, physical sleep, secure keyguard or optical usability.
