# Native Mode and coordinate rendering checks

Run `python3 directrg/src/test/mode-routing/run.py --output-dir /absolute/new/path`.
The runner compiles the actual NativeFunctionInput, FunctionGestureRecognizer, PointerOverlay,
HeadPointerModel and shared DTO/codec sources. Android broadcast/clock/View/Canvas and raw sensor
acquisition are explicit host boundaries. It verifies short once/long no action, raw/OEM duplicate
handling, old-source retirement, pointer pinning and ordered numbered Settings markers.

Recorded Canvas commands establish the source rendering path, not an Android/GPU or optical
measurement. Main compilation, installed artifacts and both-device E2E belong to the Manager.
