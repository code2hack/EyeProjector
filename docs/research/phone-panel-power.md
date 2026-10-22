# Physical Phone panels off while projection continues

Owner decision, 2026-10-20: the Phone home-screen widget provides **Start / Stop** and **Panel off**. [SPEC.md](../../SPEC.md#phone-home-screen-widget) records this user-facing trigger; projection continues while Android remains Awake.

## Mechanism

The operation powers down the physical displays while retaining an active virtual display and an Awake Android system. Keyguard remains a separate state. The pinned scrcpy backend exposes display-power controls: its desktop client uses `--turn-screen-off` / `-S`, or `MOD+o` during a session and `MOD+Shift+o` to restore power. These are software controls rather than an ordinary Phone gesture. [Pinned device controls](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/device.md#turn-screen-off)

For a new projection display, the backend targets physical displays for this operation, using SurfaceControl OFF/NORMAL modes. Its keep-active mechanism separately signals user activity to prevent inactivity sleep. The plugged-in stay-awake option has a different scope and is ineffective while unplugged. [Controller](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/Controller.java), [physical display control](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/device/Device.java), [keep-active and stay-awake](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/device.md#keep-active)

The selected **Panel off** widget action will use the Shizuku-backed host during active projection. Stop, Disconnect and session failure restore display power after session-owned blanking. This makes panel OFF an EyeProjector UI operation after setup; its implementation and device verification are pending. A universal stock Android UI action for this exact state has not been established.

## Inactivity while connected

The current [host startup](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/ShellMain.kt) already supplies `keep_active=true`. The pinned Controller uses a separate thread to signal Android user activity every four seconds, independently of incoming taps or head movement. That is an inactivity-prevention mechanism while Android is Awake, rather than a wake-up mechanism after deliberate sleep. Its thread stops with the backend; the host's session teardown terminates that backend. A paired but disconnected host supplies no such activity. See the linked Controller and [Device helper](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/device/Device.java).

The product expectation is continued projection with panels off throughout an active session, including quiet reading. Deliberate sleep, platform intervention and backend failure retain their own limits; keep-active is not an unconditional uptime guarantee. Sustained no-input operation still needs device verification on EyeProjector.

## Evidence and limits

The [retained Fold6 experiment](../provenance/pending-panel-power-results.md) used software OFF/ON commands. It recorded both physical compositor modes OFF while Android remained Awake and the virtual display continued, with an Owner observation of the active panel dark and projected video moving. Ordinary locking had earlier produced Dozing and interrupted the target, so those actions have different evidence.

The ten-minute trial failed with a receiver decoder stall. Fresh capture startup while already blanked remains unqualified. Cleanup restored software modes ON, rather than exactly restoring the initial per-panel foldable configuration. The result is bounded historical evidence, not device acceptance of EyeProjector.

The shell display-power commands mentioned in the upstream documentation use another API path. The pinned backend explicitly disables that newer path because it does not behave as expected. This note follows the demonstrated SurfaceControl route rather than treating those commands as equivalent. See the linked Device implementation.

No device operation, power setting, implementation or live resource ownership was changed for this explanation.
