# Coordinate mode routing checks

The maintained command is `python3 directhost/src/test/hid-v04/run.py --output-dir /absolute/new/path`.
It compiles the actual HID store/controller/merger, Phone mode owner, coordinate reducer/store,
input router and ordinary dispatcher/queue. The only app-process boundaries are Android Context
and the local Binder transport interfaces; no second mode implementation appears in the harness.

Concurrent tests hold Mode delivery while later Tap DOWN/UP, Back and Scroll commit. Releasing Mode
must make every later decision observe Settings, with no target-app input. Persistence, publication
and output assert that the control lock is free. Other cases exercise persistence failure, LIFO,
app/Home/restart state, ordinary drag, coordinate pinning and matched wire framing. Native DOWN
followed by consumed HID/native Scroll must cancel its original contact once before selection and
reject its later UP/CANCEL without affecting a fresh contact; Settings remains free of app input. The tests do not
execute the full Android Session, native interception, firmware or physical devices.
