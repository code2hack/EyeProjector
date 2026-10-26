# Producer failure and ownership checks

These checks compile both complete production Java files, separately for the closed
parent and current candidate. There is no copied callback/start/close implementation
or extracted-method model. The same harness reaches the private service constructor,
register and command loop by reflection; callback dispatch uses the actual production
callback saved by a fake service. It retains one token object and an unrelated fake
camera while observing actual register/status/unregister/quit code.

The historical `--scenario failure` harness has two parent scenarios: the deliberately
reproduced masked-null failure and the unchanged normal token protocol. It has 23
candidate scenarios covering
null/invalid Surface, five start stages, false EGL returns, primary plus independent
cleanup exceptions, first-draw failure, callback provenance before queue handoff,
capture requests, controlled frame progression, same-ID replacement, retired/rejected
callbacks and negative cleanup with the original token retained.
Those earlier results remain historical evidence; selecting the inventory harness
does not rerun or claim the old 23+2 as current verification.

`--scenario inventory` selects the targeted213 harness. Its three parent201
counterexamples cover a constructed Surface paused before Handler enqueue, a later
failed release after the sweep's premature positive, and a callback arriving after
the initial empty sweep. Its ten current scenarios cover successful/failed pre-post
sweeps, late retired and rejected callbacks with both release outcomes, a queued
callback not yet in the stream map, normal configured/process/close behavior,
first-start failure plus independent EGL/Surface cleanup faults, and whole-stream
EGL cleanup failure despite successful Surface disposal. Checks observe the actual
actor's inventory identity, revision, counts and `KNOWN_AT_OBSERVATION` scope. A
test-only latest producer-event selector demonstrates that older positive markers
cannot represent newer pending or failed ownership. It changes no private caller.

`doubles/` contains only type/behavior substitutes. AIDL interfaces mirror the pinned
method/configuration shapes; they perform no Parcel decoding, real Binder, oneway IPC
or Android class loading. Surface counts release attempts and can throw. EGL/GLES
return sentinels, fill output arrays and inject stage faults; they render no pixels.
JSONObject captures structured production events and emits simple JSON. Its recorded
class origins are host-double origins, not Android implementation evidence.

Handler queues immediate work rather than running inline. Delayed ticks never loop
automatically. HandlerThread starts no native/JVM background thread; its inherited
join therefore proves nothing about the real Android producer thread or process.
The inventory harness separately creates 13 real, controlled JVM workers across its
13 scenarios: two independent callback/command lanes in each pre-post sweep case,
and one callback lane in each late-arrival case. The post hook pauses before enqueue
and outside the queue lock. Every controlled gate is released in `finally`; every
started worker receives a join attempt, with a fixture-only interrupt/rejoin on a
timeout. Cleanup failures are suppressed onto the original scenario failure and
the hook is always cleared. A successful scenario asserts no workers remain alive.
An uncooperative worker makes the check fail; the runner's owned JVM group cleanup
then supplies the process-closure evidence, not a claim that this worker joined.
The runner separately records and reaps its actual local javac/JVM process groups.
The parent exception escaping a queued task is a host counterexample, not an observed
Samsung exception origin or proof that its actual process could survive the fatal.

Run only when the assigned cached host-check window is authorized:

```sh
python3 tools/camera-access/test/run.py \
  --scenario failure \
  --parent-source-dir /ABSOLUTE/CLOSED201/parent/tools/camera-access/src \
  --output-dir /ABSOLUTE/PRIVATE201/NEW-ATTEMPT
```

For the targeted213 checks, use a fresh assigned evidence directory and the closed
parent201 source snapshot:

```sh
python3 tools/camera-access/test/run.py \
  --scenario inventory \
  --parent-source-dir /ABSOLUTE/PRIVATE213/parent201/source/tools/camera-access/src \
  --output-dir /ABSOLUTE/PRIVATE213/NEW-ATTEMPT
```

The runner requires a new evidence directory, snapshots/hashes both complete producer
sources, the selected harness, every compiled double, runner and this README, and
retains failed stages and logs. Cached JDK17 compiles/runs parent then
current sequentially within one 120-second total check budget. It uses no SDK, AIDL
generator, Gradle, D8, network or device command. Any cleanup signals apply solely to
its local compiler/test JVM process groups, never to a registered producer or service.
Original input hashes are checked again at completion; the result distinguishes each
child exit, primary error, local cleanup, leader wait and process-group disappearance.

Passing checks establish the exercised Java error/ownership behavior under these
doubles. They establish no stock API/ABI, permissions, real callback Surface, EGL,
imagery, physical-device outcome, actual owner lifecycle or orphan-camera recovery.
