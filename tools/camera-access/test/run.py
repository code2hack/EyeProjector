#!/usr/bin/env python3
"""Compile full parent/current producers against local TYPE DOUBLES; no SDK/device/build tool."""
import argparse
from contextlib import contextmanager
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time


SUITE = Path(__file__).resolve().parent
PRODUCER = Path("com/code2hack/eyeprojector/cameraaccess")
TOTAL_SECONDS = 120


def interrupted(signum, frame):
    raise KeyboardInterrupt("Owned host-check signal " + str(signum))


@contextmanager
def defer_signals():
    """Publish spawned ownership and finish local cleanup before reacting to signals."""
    pending = []
    previous = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
    for number in previous:
        signal.signal(number, lambda signum, frame: pending.append(signum))
    try:
        yield pending
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_once(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def group_members(pgid):
    result = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            raw = (entry / "stat").read_text()
            fields = raw.rsplit(")", 1)[1].split()
            if int(fields[2]) == pgid:
                result.append({"pid": int(entry.name), "state": fields[0], "startTicks": int(fields[19])})
        except (OSError, ValueError, IndexError):
            continue
    return result


def stage(argv, directory, label, deadline):
    intent = {"startedAt": now(), "argv": argv, "cwd": str(directory),
              "scope": "Only this local cached javac/JVM type-double child group",
              "remainingTotalSeconds": max(0, deadline - time.monotonic())}
    write_once(directory / (label + "-intent.json"), intent)
    result = {**intent, "pid": None, "pgid": None, "exitCode": None,
              "primary": None, "cleanupErrors": [], "signals": []}
    process = None
    log = directory / (label + ".log")
    try:
        remaining = deadline - time.monotonic()
        if remaining <= 3:
            raise TimeoutError("Total check budget exhausted before child spawn")
        with log.open("x", encoding="utf-8") as stream:
            # A separate process group contains only this test's compiler/JVM.
            # No actual producer/ADB/service process is started by this runner.
            with defer_signals() as spawn_signals:
                process = subprocess.Popen(argv, cwd=directory, stdout=stream,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                result["pid"] = result["pgid"] = process.pid
                result["initialGroupMembers"] = group_members(process.pid)
                write_once(directory / (label + "-running.json"), result)
            if spawn_signals:
                raise KeyboardInterrupt("Deferred owned spawn signals " + repr(spawn_signals))
            result["exitCode"] = process.wait(timeout=max(0.01, deadline - time.monotonic() - 3))
    except BaseException as error:
        result["primary"] = {"type": type(error).__name__, "error": repr(error)}
    finally:
        if process is not None:
            # Only owned HOST TEST groups may be signalled. This never governs a
            # real registered owner and introduces no product/owner lifetime cap.
            with defer_signals() as cleanup_signals:
                try:
                    if process.poll() is None or group_members(process.pid):
                        try:
                            os.killpg(process.pid, signal.SIGTERM)
                            result["signals"].append("SIGTERM_OWNED_HOST_TEST_GROUP")
                        except ProcessLookupError:
                            pass
                        try:
                            process.wait(timeout=min(1, max(0.01, deadline - time.monotonic())))
                        except subprocess.TimeoutExpired:
                            pass
                        if process.poll() is None or group_members(process.pid):
                            try:
                                os.killpg(process.pid, signal.SIGKILL)
                                result["signals"].append("SIGKILL_OWNED_HOST_TEST_GROUP")
                            except ProcessLookupError:
                                pass
                    process.wait(timeout=min(1, max(0.01, deadline - time.monotonic())))
                except BaseException as error:
                    result["cleanupErrors"].append({"type": type(error).__name__, "error": repr(error)})
                result["waitExitCode"] = process.returncode
                result["leaderReaped"] = process.returncode is not None
                result["terminalGroupMembers"] = group_members(process.pid)
                result["groupGone"] = not result["terminalGroupMembers"]
            result["deferredCleanupSignals"] = cleanup_signals
            if cleanup_signals and result["primary"] is None:
                result["primary"] = {"type": "KeyboardInterrupt", "error": repr(cleanup_signals)}
        else:
            result.update(leaderReaped=True, groupGone=True, terminalGroupMembers=[])
        result["finishedAt"] = now()
        if log.exists():
            result["logSha256"] = digest(log)
        result["passed"] = (result["exitCode"] == 0 and result["primary"] is None
                            and not result["cleanupErrors"] and result["leaderReaped"] and result["groupGone"])
        write_once(directory / (label + "-result.json"), result)
    return result


def main():
    begun = time.monotonic()
    deadline = begun + TOTAL_SECONDS
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--parent-source-dir", required=True, type=Path,
                        help="Closed parent tools/camera-access/src directory for the selected scenario")
    parser.add_argument("--current-source-dir", type=Path, default=SUITE.parent / "src")
    parser.add_argument("--java-home", type=Path, default=Path("/usr/lib/jvm/java-17-openjdk-amd64"))
    parser.add_argument("--scenario", choices=("failure", "inventory"), default="failure",
                        help="inventory runs targeted213 concurrency checks without rerunning the old23")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    result = {"startedAt": now(), "budgetSeconds": TOTAL_SECONDS,
              "scope": "Full unchanged production Java compiled/run with test TYPE DOUBLES only",
              "scenario": args.scenario,
              "limits": ["No Android/SDK/AIDL generation/Binder wire/service/permission verification",
                         "No native Surface/EGL/GLES/image/renderer-thread or real owner lifecycle proof",
                         "HandlerThread does not start; queued immediate tasks are drained explicitly",
                         "Inventory mode uses real controlled fixture workers, not Android Binder/renderer threads",
                         "Delayed rendering is advanced at most one task when requested by a scenario",
                         "Parent failures are controlled source counterexamples, not Samsung root cause",
                         "Known inventory state at observation is no future-callback/quiescence guarantee"],
              "inputs": [], "stages": [], "exit": 1}

    def snapshot(source, relative, label):
        source = source.resolve()
        destination = output / "snapshot" / label / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(source.read_bytes())
        result["inputs"].append({"source": str(source), "snapshot": str(destination.relative_to(output)),
                                 "sha256": digest(destination), "bytes": destination.stat().st_size})
        return destination

    try:
        doubles = [snapshot(p, p.relative_to(SUITE), "suite") for p in sorted((SUITE / "doubles").rglob("*.java"))]
        harness_name = "InventoryScenarios" if args.scenario == "inventory" else "ProducerScenarios"
        harness = snapshot(SUITE / "java" / PRODUCER / (harness_name + ".java"),
                           Path("java") / PRODUCER / (harness_name + ".java"), "suite")
        snapshot(Path(__file__), Path("run.py"), "suite")
        snapshot(SUITE / "README.md", Path("README.md"), "suite")
        sources = {}
        for label, source_root in (("parent", args.parent_source_dir), ("current", args.current_source_dir)):
            sources[label] = [snapshot(source_root / PRODUCER / name, PRODUCER / name, label)
                              for name in ("CameraAccessMain.java", "SyntheticStream.java")]
        result["javaHome"] = str(args.java_home.resolve())
        result["toolSha256"] = {name: digest(args.java_home / "bin" / name) for name in ("javac", "java")}
        write_once(output / "inputs.json", result)
        for label in ("parent", "current"):
            classes = output / (label + "-classes")
            classes.mkdir()
            commands = (
                (label + "-compile", [str(args.java_home / "bin" / "javac"), "--release", "17",
                                      "-classpath", str(classes), "-d", str(classes),
                                      *map(str, doubles), str(harness), *map(str, sources[label])]),
                (label + "-scenarios", [str(args.java_home / "bin" / "java"), "-cp", str(classes),
                                       "com.code2hack.eyeprojector.cameraaccess." + harness_name, label]),
            )
            for name, argv in commands:
                observed = stage(argv, output, name, deadline)
                result["stages"].append(observed)
                print(name + ": " + ("PASS" if observed["passed"] else "FAIL"), flush=True)
                if not observed["passed"]:
                    raise RuntimeError("Retained failed stage: " + name)
        result["exit"] = 0
    except BaseException as error:
        result["primary"] = {"type": type(error).__name__, "error": repr(error)}
    finally:
        result["inputsUnchanged"] = all(Path(row["source"]).is_file()
                                         and digest(Path(row["source"])) == row["sha256"]
                                         for row in result["inputs"])
        result["elapsedSeconds"] = time.monotonic() - begun
        result["finishedAt"] = now()
        if not result["inputsUnchanged"] or result["elapsedSeconds"] > TOTAL_SECONDS:
            result["exit"] = 1
        result["allSpawnedGroupsClosed"] = all(stage["leaderReaped"] and stage["groupGone"]
                                                for stage in result["stages"])
        if not result["allSpawnedGroupsClosed"]:
            result["exit"] = 1
        result["logHashes"] = {p.name: digest(p) for p in output.glob("*.log")}
        write_once(output / "result.json", result)
        print(json.dumps({"exit": result["exit"], "evidence": str(output),
                          "elapsedSeconds": result["elapsedSeconds"],
                          "allSpawnedGroupsClosed": result["allSpawnedGroupsClosed"]}), flush=True)
    return result["exit"]


if __name__ == "__main__":
    raise SystemExit(main())
