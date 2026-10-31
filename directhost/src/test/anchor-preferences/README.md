# Anchor preference host checks

Run `python3 directhost/src/test/anchor-preferences/run.py --output-dir /path/to/evidence`.
The runner uses the cached Kotlin compiler and JUnit without Gradle, SDK or a device. It records
source/dependency hashes, commands, logs and the output JAR hash. The normal JUnit source also runs
in `directhost`'s Gradle unit-test source set. A separate host-only test doubles Android Context/Log
to check the actual Android adapter's application-context/process-instance boundary.

The production codec, store and file persistence run unchanged against real temporary files.
Checks cover both explicitly chosen modes, nullable Activity/static target metadata, package-level
replacement, fresh-instance round-trips, Clear isolation, no partial-record publication, committed
change ordering, concurrent reads, package-filtered subscription lifetimes and write/consumer failures.
Actual existing chosen-app/scale/notification models exercise retained independent preferences.

`AppAnchorPreferences.open(context)` returns the same store throughout the app process. Registration
must call `replace(AppAnchor(descriptor, chosenMode))` only after the user completes Click/Hold choice;
Cancel/incomplete registration makes no store call. A package has one record, irrespective of Activity.
Only the explicit `clear(package)` deletes it; chosen-app removal does not call Clear. Descriptor
matching retains accepted118's static resource-ID/class/optional-Activity coverage limits. There are
no saved nodes, windows, sessions, touch contacts, coordinates or inferred activation modes.

Storage commits a whole versioned descriptor/mode record using the repository's existing NIO atomic
replacement route; [Android Files.move](https://developer.android.com/reference/java/nio/file/Files#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...))
defines the platform operation. File bytes are synced before rename. This is not a power-loss or
physical-device persistence qualification. Interrupted temporary files are not lookup candidates.

Changes are typed `Replaced(previous,current)` or `Cleared(previous)`. Every completed replacement
emits, including an equal value; Clear of an absent record is a no-op. Callbacks run synchronously
after successful persistence, under the instance's serialization lock and before the mutation returns
or other-thread store reads proceed. Callbacks may read/unsubscribe but must not mutate the store or
wait for another thread using it. Observer RuntimeExceptions are reported separately and cannot undo
the committed change or starve the remaining observers. Subscribing/reopening replays no events.

The future shared contact owner subscribes to the active package for its entire input-context lifetime
and retires the original contact synchronously on replacement/Clear before further input use. The menu
subscription lasts only while its dialog exists and posts visual refreshes to its view. This issue
provides a preference seam only: it does not implement or prove held-contact retirement. Registration,
contact ownership, actual Android menu rendering/accessibility, device persistence and final app/input
coverage remain downstream integration/assembled-device validation. No device or app effects are
inferred from these host checks.
