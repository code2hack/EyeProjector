# HID navigation and semantic binding checks

The shared `HidNavigationGestureRecognizer` uses the existing 500 ms click interval. One click commits Back, two Lock, and three Home. Single and double wait for another click; triple commits once. Original sequence membership, cancellation/context loss, late timers and fresh recovery are tested.

The five binding roles are Tap, Scroll down, Scroll up, Anchor, and Back / Lock / Home. Both stores preserve explicit assignments and load reset settings without repopulating old roles. Anchor short presses remain independent FIFO activations, and its long press registers an Anchor; the native physical Function remains a separate input source.

`directhost/src/test/anchor-cancel-only/run-regressions.py` compiles the actual shared grammar and binding/menu canonical tests with frozen dependencies. The composed `run.py` additionally exercises the real Phone controller/merger and source/transaction paths. Android widget/service and physical behavior retain their stated boundaries; a cached test does not establish device acceptance.
