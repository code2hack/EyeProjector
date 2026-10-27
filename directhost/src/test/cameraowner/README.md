# Prepared product camera-owner host controls

Source preparation only. No runner, compiler, generated code or JVM was invoked
while preparing these files. A separate exact Root host/tool/input/output grant
must authorize execution. These controls do not register a native camera.

The future closure compiles the actual four product Kotlin files and three
scenario files against the actual nine generated QPR2 Java bodies selected by a
Root-pinned binding. There are no handwritten virtualcamera wire classes. The
old r1 classes, SDK Android framework jar and private producer Main/SyntheticStream
are absent from this fixture classpath. Reusing SDK404 wire generation is not a
claim that the new product module has passed its own SDK build.

`OwnerScenarios.kt` supplies the single main and `runOwnerScenarios(): Int`;
`LeaseScenarios.kt` supplies `runLeaseScenarios(): Int`;
`GeneratedContractScenarios.kt` supplies `runGeneratedContractScenarios(): Int`.
Each suite reports its actual case records separately. Historical host410's
20 cases are not rerun or added to the new product-case count.

One explicit ambiguous-unregister fault control injects a HOST-ONLY daemon
start, leaving that original model native lane Pending after a thrown unregister
and fresh empty ID. It reports
`OBSERVED hostDaemonModelDuty=PENDING_ORIGINAL_UNREGISTER actualProductJoinProof=false`.
The JVM can then terminate without an explicit exit/kill; JVM exit0 or process
group absence does not prove that model lane joined or completed natural product
cleanup. The product's default workers remain non-daemon. Preserve the Pending
snapshot/observation; other successful controls observe their own original joins.

## Exact future binding and invocation

Root prepares an external JSON source binding after the source freeze:

```json
{
  "schemaVersion": 1,
  "sourceRoot": "<exact assigned worktree>",
  "generatedBindingSha256": "<exact Root-selected binding file SHA256>",
  "mainClass": "com.code2hack.eyeprojector.directhost.camera.OwnerScenariosKt",
  "files": [
    {"path": "<relative path>", "sha256": "<SHA256>", "bytes": 0, "mode": "0o664"}
  ],
  "tools": [
    {"path": "<absolute tool file>", "sha256": "<SHA256>", "bytes": 0, "mode": "0o755"}
  ]
}
```

`files` contains exactly the30 paths enumerated in `run.py`: product4,
scenario3, platform doubles12, exact pinned AIDL9, `run.py` and this README.
The generated binding uses the existing `generatedRoot` / `generatedJava`
nine-row schema, with actual path/hash/size provenance. Root may select the
completed SDK404 set (sourceSet9073) using exact binding410f80e3d80, or supply a
fresh explicitly pinned actual generation. It must never substitute a template
or a handwritten wire twin.

`tools` names the explicit java, javac, kotlinc launcher and kotlin-stdlib paths
plus the launcher's actual compiler/dependency bodies under Root's grant.
The runner hashes those files as data; it performs no version/help probes,
downloads, SDK generation or compiler discovery. Tool paths come from CLI,
never `kotlinc` on PATH. Root also pins this source-binding file in the grant.

```text
/usr/bin/python3 directhost/src/test/cameraowner/run.py
  --source-binding <Root-frozen-source-binding.json>
  --generated-binding <Root-selected-actual-generated-binding.json>
  --java-home <exact-JDK17-root>
  --kotlinc <exact-cached-kotlinc-launcher>
  --kotlin-stdlib <exact-kotlin-stdlib.jar>
  --output <fresh-absent-one-closure-directory>
```

This is argv metadata, not execution authority. The runner creates output once,
snapshots bindings/all30 source inputs/actual generated9 Java and verifies pins.
It invokes one javac over generated9+doubles12, one kotlinc over product4+tests3,
then one scenario JVM. Java and Kotlin outputs use separate fresh directories.
The shared120s budget includes preparation and all stages, with owned-process
cleanup reserved inside it. No failed stage is repaired or rerun.

Each stage preserves intent/running/result JSON, stdout/stderr and sampled
original PID/birth/PG observations plus direct leader wait/reaping. A remaining
original group is a failed/incomplete closure, never silently adopted or globally
stopped. Sampling cannot prove continuous/exhaustive descendant absence. First
failures and partial outputs are retained. The final `result.json` preserves the
first outcome; it is not a formal review or product acceptance.

## Platform-model origins and limits

The base eight Java doubles were copied as saved bytes from
`issue-67/qpr2-host410/worker410/saved-closure410/snapshot/fixtures/doubles/`,
whose actual generated source came from SDK404. These are explicit models,
not Android runtime classes. The following original SHA256 pins are preserved
as provenance; Binder and Surface then receive only the extensions below.

| Original double | Original SHA256 |
| --- | --- |
| BadParcelableException.java | e502544d64ba429c77a2c8a0d76c4414445048b4b5c57d3af2f91a3577dfcc05 |
| Binder.java | f23aa87341aaa09a587bfddfde936b7f9adcdcf0d34abeb1de6e67b6fc6f8bc1 |
| IBinder.java | 49d336d9a12f9cde735fe4f8673086fcff5fe02b95b34e91d1abbf8a9ffa9a1b |
| IInterface.java | 1105f400dc65297a4c7041ef5bf8671c9af16758099d40ef68d2fc3d0eda49cb |
| Parcel.java | 90bb7ca9d8cffc06c2cdc45e5b2b9d58e7a94c8e998ec150938eb9602ee1fda3 |
| Parcelable.java | a60fd029ef80364869ed0d5f3bb1c0a0bae08a2d1b72d7ee0c909b3b1122d4ca |
| RemoteException.java | 7e64175508cdefe262451af280fa3bbb94afcbe14886dc15a0719ea90fe092a1 |
| Surface.java | 7e955baf6964633e467a2b01393030e09b359ea7719dee9a28936c843d4f03bf |

Binder adds configurable static calling UID/PID observations. Surface preserves
the artificial one-int parcel payload and adds release-attempt counting,
throwing release, a host-only controlled release hook, and success/validity observations. Process, Build, SystemClock
and Context are new minimal host shapes for the actual product code. They
perform no permission grant, service lookup or Android/native action.

Parcel remains a typed-cell model: int/boolean4, long8, arbitrary in-process
Binder-reference8 slot, UTF16-like string accounting without policy headers,
and byte-array padding4. The bridge is synchronous in-process dispatch even
when generated flags say oneway. There are no native handles, FDs, IPC, genuine
Surface serialization, Camera2, EGL, decoder output or physical camera effects.
Opaque metadata fixtures are not native camera_metadata buffers. Immediate
Binder identity is not the camera-using application's UID. A successful host
closure establishes none of Samsung ABI, callback quiescence, fullH, ordinary
app selection, rendered imagery or issue/version acceptance.
