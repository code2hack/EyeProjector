# Paired-session cancellation scheduling regressions

After affected Gradle checks provision Kotlin and compile the protocol module, run with JDK 17:

```sh
python3 directrg/src/test/adapter/run-session-cancellation.py --output-dir /path/to/private/evidence
```

The harness compiles the actual `PairedSessionClient`, `SessionController` and `SessionAttachment` production sources. The candidate uses the same controller command dispatcher as the Phone adapter. Its transport models admission/failure; an undrained queue models Android UI delivery. The original six scenarios cover queued availability, competing Start epochs, polling, fresh Phone Start, independent final Stop failures, errors and reentrant callbacks. Eight additional scenarios cover an owner first revealed in flight, lost/canceled/late replies, cancellation before Start admission, failed cleanup before/after server processing, Phone process recreation through UNKNOWN, and fresh Phone/Glasses starts. Phone JVM tests separately exercise the real atomic correlation store and recreated controllers.

`--source-dir` selects an exact repository source snapshot. The factory supports the frozen four-argument client by reproducing its Activity-side queue, and the corrected five-argument client by supplying that queue to the client. It implements no generation check itself. Parent controller snapshots use their old dispatcher semantics. `--expect-fail` requires compiled failures of the original R8-01/R8-02 cases by default; use `--expected-failure 'FAIL R8-01 in-flight owner canceled-reply'` for the review02 parent. Compilation failure never counts. Source, protocol-class and log hashes are retained. These are host scheduling regressions, not Android/device or remote resource-release evidence.

The transaction suite also compiles the real app-private atomic store and the control codec sources:

```sh
python3 directrg/src/test/adapter/run-transactions.py --output-dir /path/to/private/transactions
python3 directrg/src/test/adapter/run-transactions.py --revision 85cc031473fd1809b581b9cd979ec7947c6741e3 --expect-baseline-failure --output-dir /path/to/private/parent-transactions
```

Its eight scenarios cover Disconnect during the initial STATUS, cancellation overtaking STATUS admission, a failed initial transport, queued backend Stop lost at Phone process death, a closed Receiver leaving durable UNKNOWN cancellation, terminal atomic-store failure, repeated cancellation with a fresh owner, and cancellation after UNKNOWN resolves. The identical harness must compile the preserved parent and reproduce six failures, while retaining its two positive cases. Candidate receipts distinguish durable pending cancellation from verified release; attachment requires the current attempt's observed owner. The bootstrap adapter suite separately drives the actual main observer through terminal store failure. Host filesystem and scheduled API doubles do not qualify Android persistence or Binder behavior.
