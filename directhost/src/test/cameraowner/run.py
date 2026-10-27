#!/usr/bin/env python3
"""Prepared cameraowner host closure. Do not invoke, import or probe before a fresh grant.

One javac, one explicitly bound kotlinc, one scenario JVM; no SDK/generation/device calls.
Actual generated QPR2 Java is compiled against named platform doubles, never wire twins.
"""
import argparse
from contextlib import contextmanager
import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TOTAL_SECONDS = 120
PACKAGE = "com/code2hack/eyeprojector/directhost/camera/"
TYPES = ("Format", "LensFacing", "SensorOrientation", "SupportedStreamConfiguration",
         "VirtualCameraConfiguration", "IVirtualCameraService", "IVirtualCameraCallback",
         "VirtualCameraMetadata", "ICaptureResultConsumer")
AIDL_HASHES = {
    "Format": "b49de35df97b4c188ce7157f030574f7616293798b7a933226554d394715c4e4",
    "LensFacing": "f15c637f42431a15140efd82648ffc102335a890c57f6043404f121355334c72",
    "SensorOrientation": "91ba7f89d6a7423eef558f36f390aaaffbc23c37c7f6d23f5e77304aa17de4f4",
    "SupportedStreamConfiguration": "a2eb8c1a62d18c30ac347d347a18d94a6152c38968bbadc3792c410a12cba2a3",
    "VirtualCameraConfiguration": "9994250f96b032aadf4ca690f88f0e42c0540ec108b557a2b0e808a00884413e",
    "IVirtualCameraService": "ec22bf6caec538dadc3bf8755421517f8a800e28c97e98b7896a0a54db67d4fc",
    "IVirtualCameraCallback": "02ba54bac8a0258029196646ec0d2ceb60087929c5f373a55ecf8fc619360608",
    "VirtualCameraMetadata": "b0f188be9d45a76000d142c80592d0dbc616c4d215083d74a9e4f739038302f8",
    "ICaptureResultConsumer": "70f0479bd729ce197e81e8ef2465f9cf8ff95f8d46b3148b1ecccb9f04ecc509",
}
PRODUCT = tuple("directhost/src/main/kotlin/" + PACKAGE + name + ".kt" for name in
                ("Qpr2VirtualCameraOwner", "PhoneCameraLease", "PhoneCameraWorker", "Qpr2CameraPlatform"))
SCENARIOS = tuple("directhost/src/test/cameraowner/" + name + ".kt" for name in
                 ("OwnerScenarios", "LeaseScenarios", "GeneratedContractScenarios"))
DOUBLE_NAMES = tuple("android/os/" + name + ".java" for name in
                     ("BadParcelableException", "Binder", "IBinder", "IInterface", "Parcel",
                      "Parcelable", "RemoteException", "Process", "Build", "SystemClock")) + (
                          "android/view/Surface.java", "android/content/Context.java")
DOUBLES = tuple("directhost/src/test/cameraowner/doubles/" + name for name in DOUBLE_NAMES)
AIDL = tuple("directhost/src/main/aidl/android/companion/virtualcamera/" + name + ".aidl" for name in TYPES)
SUPPORT = ("directhost/src/test/cameraowner/run.py", "directhost/src/test/cameraowner/README.md")
SOURCE_FILES = PRODUCT + SCENARIOS + DOUBLES + AIDL + SUPPORT
MAIN_CLASS = "com.code2hack.eyeprojector.directhost.camera.OwnerScenariosKt"


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_once(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def identity(pid):
    try:
        directory = Path("/proc", str(pid))
        fields = (directory / "stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": int(pid), "state": fields[0], "ppid": int(fields[1]),
                "pgid": int(fields[2]), "session": int(fields[3]), "birthStartTicks": int(fields[19]),
                "cmdline": (directory / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")}
    except (OSError, ValueError, IndexError):
        return None


def group_point(pgid):
    values = []
    for directory in Path("/proc").iterdir():
        if directory.name.isdigit():
            value = identity(directory.name)
            if value and value["pgid"] == pgid:
                values.append(value)
    return sorted(values, key=lambda value: value["pid"])


def interrupted(number, frame):
    raise KeyboardInterrupt("Owned host closure received signal " + str(number))


@contextmanager
def publish_spawn_ownership():
    pending = []
    old = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
    for number in old:
        signal.signal(number, lambda number, frame: pending.append(number))
    try:
        yield pending
    finally:
        for number, handler in old.items():
            signal.signal(number, handler)


def run_stage(label, argv, out, env, deadline):
    record = {"label": label, "startedAt": utc(), "argv": argv, "cwd": str(out), "env": env,
              "exit": None, "primary": None, "signals": [],
              "scope": "New compiler/scenario JVM group only; no Android owner or device"}
    write_once(out / (label + "-intent.json"), record)
    child = None
    first_seen = {}
    try:
        if time.monotonic() >= deadline - 3:
            raise TimeoutError("Shared120s closure budget exhausted before stage spawn")
        with (out / (label + ".stdout")).open("x") as stdout, (out / (label + ".stderr")).open("x") as stderr, \
                (out / (label + "-process-points.jsonl")).open("x") as trace:
            with publish_spawn_ownership() as pending:
                child = subprocess.Popen(argv, cwd=out, env=env, stdout=stdout, stderr=stderr,
                                         start_new_session=True)
                initial = identity(child.pid)
                record.update(pid=child.pid, pgid=child.pid, initialLeaderIdentity=initial)
                write_once(out / (label + "-running.json"), record)
            if pending:
                raise KeyboardInterrupt("Deferred spawn signals " + repr(pending))
            previous = None
            while True:
                observed = group_point(child.pid)
                for value in observed:
                    first_seen.setdefault((value["pid"], value["birthStartTicks"]), value)
                if observed != previous:
                    trace.write(json.dumps({"at": utc(), "originalGroupPoint": observed}) + "\n")
                    trace.flush()
                    previous = observed
                code = child.poll()
                if code is not None:
                    record["exit"] = code
                    break
                if time.monotonic() >= deadline - 3:
                    raise TimeoutError("Shared120s closure stage budget exhausted")
                time.sleep(0.02)
    except BaseException as error:
        record["primary"] = {"type": type(error).__name__, "error": str(error)}
    finally:
        if child is not None:
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                    record["signals"].append("ORIGINAL_HOST_GROUP_SIGTERM")
                except ProcessLookupError:
                    pass
                try:
                    child.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(child.pid, signal.SIGKILL)
                        record["signals"].append("ORIGINAL_HOST_GROUP_SIGKILL")
                    except ProcessLookupError:
                        pass
            try:
                child.wait(timeout=1)
            except BaseException as error:
                record["cleanupError"] = str(error)
            record.update(waitExit=child.returncode, leaderWaitedAndReaped=child.returncode is not None,
                          originalGroupAtTerminalPoint=group_point(child.pid))
        record.update(finishedAt=utc(), observedFirstIdentities=list(first_seen.values()))
        record["passed"] = (record["exit"] == 0 and record["primary"] is None
                            and record.get("leaderWaitedAndReaped") is True
                            and not record.get("originalGroupAtTerminalPoint")
                            and not record.get("cleanupError"))
        record["processLimit"] = "Finite20ms group points and direct leader wait; missing fast descendants/births and exhaustive or continuous absence remain unknown."
        write_once(out / (label + "-result.json"), record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-binding", type=Path, required=True)
    parser.add_argument("--generated-binding", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--kotlinc", type=Path, required=True)
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, interrupted)
    begun = time.monotonic()
    deadline = begun + TOTAL_SECONDS
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    result = {"state": "HOST_CLOSURE_STARTED", "startedAt": utc(), "budgetSeconds": TOTAL_SECONDS,
              "exit": 1, "inputs": [], "stages": [], "actualAndroidWireProof": False,
              "actualProductNaturalCleanupProof": False,
              "meaning": "Actual product Kotlin and actual selected generated Java against platform doubles only"}
    try:
        source = json.loads(args.source_binding.read_text())
        generated = json.loads(args.generated_binding.read_text())
        if source["schemaVersion"] != 1 or Path(source["sourceRoot"]).resolve() != ROOT:
            raise ValueError("Source binding must name this exact cameraowner source root")
        if source["generatedBindingSha256"] != sha(args.generated_binding):
            raise ValueError("Root selected generated binding bytes changed")
        if source["mainClass"] != MAIN_CLASS:
            raise ValueError("Only the allocated cameraowner scenario main is prepared")
        rows = source["files"]
        if len(rows) != len(SOURCE_FILES) or {row["path"] for row in rows} != set(SOURCE_FILES):
            raise ValueError("Bind exactly product4/scenario3/doubles12/AIDL9/runner+README")

        def snapshot(path, relative, expected=None):
            data = path.read_bytes()
            observed = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                        "mode": oct(stat.S_IMODE(path.stat().st_mode))}
            if expected is not None and any(observed[key] != expected[key] for key in ("sha256", "bytes")):
                raise ValueError("Input bytes changed: " + str(path))
            if expected is not None and "mode" in expected and observed["mode"] != expected["mode"]:
                raise ValueError("Input mode changed: " + str(path))
            target = out / "snapshot" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(data)
            result["inputs"].append({"path": str(path), "snapshot": str(target.relative_to(out)), **observed})
            return target

        snapshot(args.source_binding.resolve(), Path("source-binding.json"))
        snapshot(args.generated_binding.resolve(), Path("generated-binding.json"))
        source_paths = {}
        for row in sorted(rows, key=lambda row: row["path"]):
            path = ROOT / row["path"]
            if row["path"] in AIDL and sha(path) != AIDL_HASHES[path.stem]:
                raise ValueError("Production AIDL differs from the pinned QPR2 definition")
            source_paths[row["path"]] = snapshot(path, Path("source") / row["path"], row)
        gen_rows = generated["generatedJava"]
        names = {"android/companion/virtualcamera/" + name + ".java" for name in TYPES}
        if len(gen_rows) != 9 or {row["path"] for row in gen_rows} != names:
            raise ValueError("One complete actual generated nine-type QPR2 set is required")
        generated_paths = [snapshot(Path(generated["generatedRoot"]) / row["path"],
                                    Path("generated") / row["path"], row)
                           for row in sorted(gen_rows, key=lambda row: row["path"])]
        result["generationQualification"] = {"bindingSha256": sha(args.generated_binding),
            "sourceSetSha256": generated.get("sourceSetSha256"), "generatedJavaCount": len(gen_rows),
            "meaning": "Root-selected actual generated Java. Reusing SDK404 wire generation does not claim a product-module SDK build."}
        tools = []
        for row in source["tools"]:
            path = Path(row["path"])
            if sha(path) != row["sha256"] or path.stat().st_size != row["bytes"]:
                raise ValueError("Tool bytes changed: " + str(path))
            if "mode" in row and oct(stat.S_IMODE(path.stat().st_mode)) != row["mode"]:
                raise ValueError("Tool mode changed: " + str(path))
            tools.append(row)
        tool_paths = {Path(row["path"]).resolve() for row in tools}
        required = {path.resolve() for path in (args.java_home / "bin/java", args.java_home / "bin/javac",
                                                args.kotlinc, args.kotlin_stdlib)}
        if not required <= tool_paths:
            raise ValueError("Pin explicit java/javac/kotlinc/stdlib tool bodies; Root also binds launcher dependencies")
        result["tools"] = tools
        env = {"JAVA_HOME": str(args.java_home.resolve()),
               "PATH": str(args.java_home.resolve() / "bin") + ":/usr/bin:/bin",
               "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "PYTHONDONTWRITEBYTECODE": "1"}
        java_classes = out / "classes-java"
        kotlin_classes = out / "classes-kotlin"
        java_classes.mkdir()
        kotlin_classes.mkdir()
        result["environment"] = env
        write_once(out / "input-binding.json", result)
        classpath = os.pathsep.join((str(java_classes), str(args.kotlin_stdlib.resolve())))
        commands = [
            ("javac", [str(args.java_home / "bin/javac"), "--release", "17", "-classpath", str(java_classes),
                       "-d", str(java_classes), *map(str, generated_paths), *[str(source_paths[name]) for name in DOUBLES]]),
            ("kotlinc", [str(args.kotlinc), *[str(source_paths[name]) for name in PRODUCT + SCENARIOS],
                         "-jdk-home", str(args.java_home.resolve()), "-jvm-target", "17", "-no-stdlib", "-no-reflect",
                         "-classpath", classpath, "-d", str(kotlin_classes)]),
            ("scenarios", [str(args.java_home / "bin/java"), "-cp",
                           os.pathsep.join((str(kotlin_classes), str(java_classes), str(args.kotlin_stdlib.resolve()))), MAIN_CLASS]),
        ]
        for label, argv in commands:
            observed = run_stage(label, argv, out, env, deadline)
            result["stages"].append(observed)
            if not observed["passed"]:
                raise RuntimeError("First failed stage preserved: " + label)
        result["modelDutyObservations"] = [line for line in (out / "scenarios.stdout").read_text().splitlines()
                                            if line.startswith("OBSERVED hostDaemonModelDuty=")]
        result["exit"] = 0
    except BaseException as error:
        result["primary"] = {"type": type(error).__name__, "error": str(error)}
    finally:
        try:
            result["inputsUnchanged"] = all(Path(row["path"]).is_file() and sha(row["path"]) == row["sha256"]
                                             for row in result["inputs"])
        except BaseException as error:
            result["inputsUnchanged"] = False
            result["postObservationFailure"] = {"type": type(error).__name__, "error": str(error)}
        result["finishedAt"] = utc()
        result["elapsedSeconds"] = time.monotonic() - begun
        if not result["inputsUnchanged"] or result["elapsedSeconds"] > TOTAL_SECONDS:
            result["exit"] = 1
        result["state"] = "HOST_CLOSURE_TERMINAL"
        result["limits"] = "Model-only source controls; no native Android Parcel/Binder/Surface behavior, Samsung ABI, Camera2/EGL/media, actual registration or product acceptance. No old20 cases are re-summed. The explicitly injected host-daemon pending-native fixture retains an original unresolved model lane: JVM exit/group absence is not that lane's actual join or natural product cleanup proof. Preserve its OBSERVED record and Pending snapshot."
        write_once(out / "result.json", result)
        print(json.dumps({"exit": result["exit"], "result": str(out / "result.json"), "actualAndroidWireProof": False}))
    return result["exit"]


if __name__ == "__main__":
    raise SystemExit(main())
