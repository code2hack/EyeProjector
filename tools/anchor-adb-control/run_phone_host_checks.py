#!/usr/bin/env python3
"""Compile actual companion state with cached Kotlin/JDK and explicit swap/store boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

CHECKS = r'''package com.code2hack.eyeprojector.directhost
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor

fun main() {
    val commands=AnchorAdbPhoneCommands()
    check(commands.next(listOf("observe","configure"))=="observe")
    check(commands.next(listOf("observe","configure"))=="configure")
    check(commands.next(listOf("observe","configure"))==null)
    check(commands.next(listOf("configure","later"))=="later" && commands.count==3)

    val old=PhoneInputTraceConfig("/mission/prior","prior")
    val own=PhoneInputTraceConfig("/mission/current","run")
    for (prior in listOf(old,null)) {
        var current=prior;val swaps=mutableListOf<PhoneInputTraceConfig?>()
        val lease=AnchorAdbPhoneTraceLease<PhoneInputTraceConfig> { value -> swaps.add(value);current.also{current=value} }
        try { lease.configure(own);check(current===own);error("test body failure") }
        catch (_: IllegalStateException) { }
        finally { check(lease.close());check(lease.close()) }
        check(current===prior && lease.prior===prior && lease.restored && swaps==listOf(own,prior))
        check(runCatching{lease.configure(own)}.isFailure)
    }

    fun snapshot(revision:Long,encoded:String?,mode:String?=null,instance:String="store")=
        AnchorAdbPhoneAnchorLedger.Snapshot(instance,revision,encoded,mode)
    for (original in listOf(snapshot(2,null),snapshot(2,"prior","CLICK"))) {
        val ledger=AnchorAdbPhoneAnchorLedger();check(ledger.backup(original)==original)
        check(ledger.backup(snapshot(99,"unrelated","HOLD"))==original)
        var mutations=0
        val restored: (AnchorAdbPhoneAnchorLedger.Snapshot,AnchorAdbPhoneAnchorLedger.Snapshot)->AnchorAdbPhoneAnchorLedger.Snapshot?=
            { expected,prior -> mutations++;prior.copy(revision=expected.revision+1) }
        check(ledger.restore(false,original,restored)=="INCONCLUSIVE_ORIGINAL_RETIREMENT_UNVERIFIED" && mutations==0)
        check(ledger.restore(true,original,restored)=="UNCHANGED_NO_WRITE" && mutations==0)
        val held=snapshot(3,"actual-registered-value","HOLD")
        check(ledger.accountRegisteredValue(held,"actual-normal-registration") == "ACCOUNTED_HOST_ATTRIBUTED_REGISTRATION")
        check(ledger.restore(true,held,null)=="UNAVAILABLE_ATOMIC_RESTORE_API")
        check(ledger.restore(true,held.copy(revision=4),restored)=="INCONCLUSIVE_CONTROLLED_VALUE_REPLACED" && mutations==0)
        check(ledger.restore(true,held.copy(instanceId="successor"),restored)=="INCONCLUSIVE_CONTROLLED_VALUE_REPLACED" && mutations==0)
        check(ledger.restore(true,held){_,_->null}=="INCONCLUSIVE_COMPARE_OR_READBACK")
        check(ledger.restore(true,held,restored)=="RESTORED_VALUE_REVISION_ADVANCED" && mutations==1)
        check(ledger.restore(true,held){_,_->original}=="INCONCLUSIVE_RESTORE_READBACK")
    }
    val noRegistration=AnchorAdbPhoneAnchorLedger();noRegistration.backup(snapshot(0,null))
    check(noRegistration.restore(true,snapshot(1,"independent","HOLD")){_,_->error("must not write")}=="INCONCLUSIVE_NO_ACCOUNTED_TEST_VALUE")

    val retired=AnchorAdbPhoneRetirement("original-owner","original-token","CLOSED",true,true,true,0)
    check(retired.matches("original-owner","original-token"))
    check(!retired.matches("original-owner","successor-token"))
    check(!retired.copy(managedState="STREAMING").matches("original-owner","original-token"))
    check(!retired.copy(originalObligations=1).matches("original-owner","original-token"))
    check(!retired.copy(originalObligations=null).matches("original-owner","original-token"))
    check(!retired.copy(terminated=false).matches("original-owner","original-token"))
    check(!retired.copy(traceEnabled=false).matches("original-owner","original-token"))
    check(!retired.copy(closing=false).matches("original-owner","original-token"))

    class Memory : AppAnchorStorage {
        val values=mutableMapOf<String,AppAnchor>();var writes=0
        override fun read(packageName:String)=values[packageName]
        override fun write(packageName:String,anchor:AppAnchor?) { writes++;if(anchor==null)values.remove(packageName)else values[packageName]=anchor }
    }
    val pkg=AnchorAdbPhoneAnchorAdapter.PACKAGE_NAME
    val held=AppAnchor(AnchorTargetDescriptor(pkg,"Fixture",1,"$pkg:id/hold","Button"),AnchorMode.HOLD)
    val independent=held.copy(descriptor=held.descriptor.copy(resourceId="$pkg:id/independent"))
    for (prior in listOf(null,held.copy(mode=AnchorMode.CLICK))) {
        val disk=Memory();if(prior!=null)disk.values[pkg]=prior // Existing fixture state at named host storage boundary.
        val store=AppAnchorStore(disk);val adapter=AnchorAdbPhoneAnchorAdapter(store);val ledger=AnchorAdbPhoneAnchorLedger()
        ledger.backup(adapter.snapshot())
        store.replace(held) // Host registration-write simulation; no Android registration claim.
        ledger.accountRegisteredValue(adapter.snapshot(),"host-registration-boundary")
        check(ledger.restore(true,adapter.snapshot()){e,p->adapter.restore(e,p).snapshot}=="RESTORED_VALUE_REVISION_ADVANCED")
        check(store.read(pkg)==prior && store.snapshot(pkg).context.revision==2L)
    }
    run {
        val disk=Memory();val store=AppAnchorStore(disk);val adapter=AnchorAdbPhoneAnchorAdapter(store)
        val prior=adapter.snapshot();store.replace(held);val expected=adapter.snapshot()
        store.replace(independent);val writes=disk.writes
        val result=adapter.restore(expected,prior)
        check(!result.applied && !result.superseded && result.snapshot==null && disk.writes==writes && store.read(pkg)==independent)
    }
    run {
        val disk=Memory();val store=AppAnchorStore(disk);val adapter=AnchorAdbPhoneAnchorAdapter(store)
        val prior=adapter.snapshot();store.replace(held);val expected=adapter.snapshot();var nested=false
        store.observe(pkg) { if(!nested){nested=true;store.replace(independent)} }
        val result=adapter.restore(expected,prior)
        check(result.applied && result.superseded && result.snapshot==null && disk.writes==3 && store.read(pkg)==independent)
        // No adapter refresh/retry can erase that independent reentrant state.
    }
    println("PASS: actual companion commands/config lease/accounting/retirement plus actual conditional181 store+codec restoration adapter: absent/present prior, conflict no-write, reentrant applied-then-superseded no retry. Config swap/storage are named host boundaries; no Android/normal START/app effect proof.")
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = root / "directhost/src/androidTest/kotlin/com/code2hack/eyeprojector/directhost"
    inputs = [source / "AnchorAdbPhoneState.kt", source / "AnchorAdbPhoneAnchorAdapter.kt"]
    checks = out / "PhoneChecks.kt"; checks.write_text(CHECKS); inputs.append(checks)
    # PhoneInputTraceConfig has only JDK dependencies; this is the declared immutable API172 source.
    config = Path("/home/code2hack/.local/state/eyeprojector/v0.3/run-001/issue-115/trace165-after65/sources/45/PhoneInputTraceConfig.kt")
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    assert sha(config) == "3e3462862472503a6e4e81286c1a21e4d3a81325f497a884547bf24b4f8e0519"
    inputs.append(config)
    contract = Path("/home/code2hack/.local/state/eyeprojector/v0.3/run-001/issue-115/conditional-restore-api181/contract.json")
    assert sha(contract) == "5444b14ffdbb30f2209dfec666ba429485047871729f8c1e28a7ff93e916127b"
    for item in json.loads(contract.read_text())["refs"]:
        if Path(item["snapshot"]).name in {"AppAnchor.kt", "AppAnchorStore.kt", "AnchorTargetDescriptor.kt"}:
            item_source = Path(item["snapshot"]); assert sha(item_source) == item["sha256"]; inputs.append(item_source)
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    coordinates = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
                   ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
                   ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
                   ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                   ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                   ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                   ("org.jetbrains", "annotations", "13.0")]
    jars = []
    for group, name, version in coordinates:
        matches = list((cache / group / name / version).glob("*/" + name + "-" + version + ".jar"))
        if len(matches) != 1:
            parser.error("Existing cached compiler input missing/ambiguous: " + name + "; no download")
        jars.append(matches[0])
    cp = os.pathsep.join(map(str, jars)); java = "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
    artifact = out / "phone-state-checks.jar"
    commands = {"compile": [java, "-Xmx512m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                             "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
                             "-d", str(artifact)] + list(map(str, inputs)),
                "checks": [java, "-Xmx128m", "-cp", str(artifact) + os.pathsep + cp,
                           "com.code2hack.eyeprojector.directhost.PhoneChecksKt"]}
    result = {"scope": "Actual pure companion state/config plus actual restoration adapter and conditional181 store/codec/descriptor; named host swap/storage boundaries. Android driver is not compiled here",
              "inputs": [{"path": str(p), "sha256": sha(p)} for p in inputs + [Path(__file__), contract]],
              "dependencies": [{"path": str(p), "sha256": sha(p)} for p in jars], "commands": commands}
    for name, command in commands.items():
        with (out / (name + ".log")).open("w") as log:
            try:
                result[name + "Exit"] = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=45).returncode
            except subprocess.TimeoutExpired:
                result[name + "Exit"] = 124
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print((out / (name + ".log")).read_text(), end="")
        if result[name + "Exit"]: raise SystemExit(result[name + "Exit"])


if __name__ == "__main__":
    main()
