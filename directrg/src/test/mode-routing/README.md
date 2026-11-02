# Native Mode and coordinate rendering checks

Run `python3 directrg/src/test/mode-routing/run.py --output-dir /absolute/new/path`.
The runner compiles the actual NativeFunctionInput, FunctionGestureRecognizer, PointerOverlay,
HeadPointerModel, NativePadShellContact and held-contact sources. It also compiles verbatim
ReceiverSessionOwner startup/attachment/retirement methods and ReceiverActivity binding/resume/focus/pause/destruction
methods using the existing source-extraction workflow. Android broadcast/clock/View/Canvas and raw sensor
acquisition are explicit host boundaries. It verifies short once/long no action, raw/OEM duplicate
handling, old-source retirement, pointer pinning and ordered numbered Settings markers.
The caller scenarios cover binding before resume, binding after focus, and retained raw listener/view
replacement. Local sockets and presentation are explicit host boundaries; no device helper is launched.

Recorded Canvas commands establish the source rendering path, not an Android/GPU or optical
measurement. Main compilation, installed artifacts and both-device E2E belong to the Manager.
