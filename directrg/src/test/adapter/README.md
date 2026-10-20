# Paired-session cancellation scheduling regressions

After affected Gradle checks provision Kotlin and compile the unchanged protocol module, run with JDK 17:

```sh
python3 directrg/src/test/adapter/run.py --output-dir /path/to/private/evidence
```

The harness compiles the actual `PairedSessionClient`, `SessionController` and `SessionAttachment` production sources. Its transport models Phone admission/failure; an undrained queue models Android UI delivery. It covers queued availability, competing Phone/Glasses Start epochs, continued polling after Disconnect, fresh Phone Start, independent final Stop failures, stale errors, current error visibility and reentrant callbacks.

`--source-dir` selects an exact repository source snapshot. The factory supports the frozen four-argument client by reproducing its Activity-side queue, and the corrected five-argument client by supplying that queue to the client. It implements no generation check itself. `--expect-fail` requires the compiled baseline to reproduce both reviewed R8-01/R8-02 failures; compilation failure does not count. Source, protocol-class and log hashes are retained. These are host scheduling regressions, not Android/device or remote resource-release evidence.
