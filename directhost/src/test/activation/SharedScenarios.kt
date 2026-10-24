package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files

private class Rig(val directory: File = Files.createTempDirectory("activation-source-").toFile()) {
    val starts = mutableListOf<String>(); val stops = mutableListOf<String>()
    var setup: String? = null
    var context = ProjectionContext()
    private var ids = 0
    private val attempts = SessionAttemptStore(File(directory, "attempts.properties"))
    val core: SessionController = SessionController(starts::add, { stops.add(core.ownership().resourceOwner.orEmpty()) },
        { "core-${++ids}" }, initialCorrelation = attempts.read(), retainCorrelation = attempts::write)
    val store = ActivationStore(File(directory,"activation.properties"))
    val engine = PhoneActivationEngine(core, store::read, store::write, { setup }, { context }, { "feature-${++ids}" })
    val incarnation = ReceiverIncarnation("receiver-one", 1)
    fun request(t: String = "T", r: String = "R") = codec(SessionControl.Request(SessionControl.Operation.STATUS,
        startEpoch = r, intent = ActivationIntent(t), incarnation = incarnation))
    fun codec(request: SessionControl.Request): SessionState {
        val input = ByteArrayOutputStream().also { SessionControl.writeRequest(DataOutputStream(it),request) }.toByteArray()
        val decoded = SessionControl.readRequest(DataInputStream(input.inputStream()))
        val reply = engine.command(decoded)
        val output = ByteArrayOutputStream().also { SessionControl.writeState(DataOutputStream(it),reply) }.toByteArray()
        return SessionControl.readState(DataInputStream(output.inputStream()))
    }
    fun owner() = core.ownership().resourceOwner!!
    fun release() { val a=owner(); core.backend(a,"STOPPED"); engine.tick() }
    fun present(revision: Long, shown: Boolean, inc: ReceiverIncarnation = incarnation): SessionState =
        presentMode(revision,if(shown)ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.NONE,inc)
    fun presentMode(revision:Long,mode:ReceiverUsabilityMode,inc:ReceiverIncarnation=incarnation,wake:Long=engine.journal.current!!.wakeRevision):SessionState {
        val c=engine.journal.current!!
        return codec(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
            presentation=PresentationReport(c.id,c.ownerEpoch,wake,inc,revision,mode)))
    }
}

fun main() {
    val tests=linkedMapOf<String,()->Unit>(
        "control3 both actual codecs retain feature and attempt scopes" to {
            val inc=ReceiverIncarnation("inc",7)
            val r=SessionControl.Request(SessionControl.Operation.STOP,"epoch","attempt",ActivationIntent("trigger"),
                CancelTrigger("T","A"),CancelActivation("H"),inc,PresentationReport("H","A",4,inc,9,ReceiverUsabilityMode.NONE,"DISPLAY_DETACHED"))
            val bytes=ByteArrayOutputStream().also { SessionControl.writeRequest(DataOutputStream(it),r) }.toByteArray()
            check(SessionControl.readRequest(DataInputStream(bytes.inputStream()))==r)
            val s=SessionState("A",SessionPhase.AVAILABLE,receipt=AttemptReceipt("R","A",AttemptDisposition.OBSERVED),
                activation=ActivationReply(ActivationDirective("H",false,4,"A",ActivationGoal.PROJECTED),TriggerReceipt("T","H",TriggerDisposition.CANCELED),"H"))
            val encoded=ByteArrayOutputStream().also { SessionControl.writeState(DataOutputStream(it),s) }.toByteArray()
            check(SessionControl.readState(DataInputStream(encoded.inputStream()))==s)
        },
        "C1 unmapped T tombstone cannot acquire unrelated UNKNOWN owner" to {
            val r=Rig()
            r.codec(SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T","R")))
            check(r.starts.isEmpty() && r.stops.isEmpty())
            r.core.restore("unrelated","READY")
            r.request()
            check(r.starts.isEmpty() && r.stops.isEmpty())
            check(r.store.read().triggers["T"]!!.canceled)
        },
        "C1 first response lost then original T cancellation reaches original H across journal restart" to {
            val r=Rig(); r.request()
            val h=r.engine.journal.current!!.id; val a=r.owner()
            val reply=r.codec(SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T","R")))
            check(reply.activation!!.trigger!!.activationId==h && reply.activation!!.trigger!!.disposition==TriggerDisposition.CANCELED)
            check(r.core.ownership().resourceOwner==a && r.core.ownership().releasing)
            val loaded=ActivationStore(File(r.directory,"activation.properties")).read()
            check(h in loaded.canceled && loaded.triggers["T"]!!.canceled)
            r.core.backend(a,"STOPPED"); r.engine.tick(); r.request()
            check(r.starts.size==1 && !r.engine.desired)
        },
        "C1 local Receiver cancels original T durably before first mapping" to {
            val dir=Files.createTempDirectory("receiver-trigger-").toFile(); val file=File(dir,"feature.properties")
            val glass=ReceiverActivationStore(file); val scope=ReceiverLaunchScope("scope","T",21)
            glass.launch(scope,"R"); glass.cancel(scope,null,"R")
            val restored=ReceiverActivationStore(file)
            check(restored.intent()==null && restored.pendingTriggers().single().admission=="R")
            val phone=Rig(); val cancel=restored.pendingTriggers().single()
            val reply=phone.codec(SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger(cancel.scope.trigger,cancel.admission)))
            restored.accept(reply.activation)
            check(restored.pendingTriggers().isEmpty()); phone.request(); check(phone.starts.isEmpty())
            restored.launch(scope,"new-R"); check(restored.intent()==null)
        },
        "actual paired client preserves original canceled T across a lost first reply" to {
            val dir=Files.createTempDirectory("paired-feature-").toFile()
            val feature=ReceiverActivationStore(File(dir,"feature.properties")); val phone=Rig()
            val mapped=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
            val acknowledged=java.util.concurrent.CountDownLatch(1)
            val scope=ReceiverLaunchScope("scope","client-T",31)
            val client=PairedSessionClient(dir,{}, {}, { request,_ ->
                val result=phone.codec(request)
                if (request.intent?.triggerId=="client-T") { mapped.countDown(); release.await(2,java.util.concurrent.TimeUnit.SECONDS) }
                if (request.cancelTrigger != null) acknowledged.countDown()
                result
            }, feature=feature)
            try {
                client.launch(scope)
                check(mapped.await(2,java.util.concurrent.TimeUnit.SECONDS))
                check(feature.state.triggers[scope.trigger]!!.activation==null)
                client.disconnect(null,scope=scope,activationId=null)
                check(ReceiverActivationStore(File(dir,"feature.properties")).pendingTriggers().single().scope==scope)
                release.countDown()
                check(acknowledged.await(3,java.util.concurrent.TimeUnit.SECONDS))
                check(!phone.engine.desired && phone.engine.journal.triggers[scope.trigger]!!.canceled)
            } finally { release.countDown(); client.close() }
        },
        "C2 whole H Stop cancels newer automatic B but delayed A cleanup cannot" to {
            val r=Rig(); r.request(); val h=r.engine.journal.current!!.id; val a=r.owner()
            r.core.backend(a,"FAILED","network"); r.engine.tick()
            check(r.starts.size==1 && r.core.ownership().releasing)
            r.release(); val b=r.owner(); check(a!=b && r.starts.size==2)
            r.codec(SessionControl.Request.cancelStart("outdated",a)); check(r.owner()==b && !r.core.ownership().releasing)
            r.codec(SessionControl.Request(SessionControl.Operation.STATUS,cancelActivation=CancelActivation(h)))
            check(!r.engine.desired && r.core.ownership().releasing && r.owner()==b)
        },
        "C2 H1 whole cancellation cannot touch H2" to {
            val r=Rig(); r.request(); val h1=r.engine.journal.current!!.id
            r.engine.cancel(h1); r.release()
            val h2=r.engine.request(ActivationCause.HID_PRESS); r.engine.tick(); val a2=r.owner()
            r.engine.cancel(h1)
            check(r.engine.handle===h2 && r.engine.desired && r.owner()==a2 && !r.core.ownership().releasing)
        },
        "C3 stale epoch caller produces no feature mutation before core rejection" to {
            val r=Rig(); val old=r.core.state().epoch; r.request(); val before=r.engine.journal
            r.engine.start(old); r.engine.stop(old)
            check(r.engine.journal==before && r.stops.isEmpty())
            val a=r.owner(); r.core.backend(a,"FAILED"); r.release(); val b=r.owner()
            r.engine.stop(a); check(r.owner()==b && r.engine.desired && !r.core.ownership().releasing)
        },
        "C3 old legacy A Stop during H1 release cannot cancel pending H2" to {
            val r=Rig(); r.request(); val h1=r.engine.journal.current!!.id; val a=r.owner()
            r.engine.cancel(h1)
            val h2=r.engine.request(ActivationCause.NOTIFICATION)
            r.engine.stop(a)
            check(r.engine.desired && r.engine.handle===h2 && r.engine.journal.current!!.id==h2.id)
            r.core.backend(a,"STOPPED"); r.engine.tick(); val b=r.owner()
            r.core.backend(b,"READY"); r.context=ProjectionContext(b,"new-target");r.engine.tick()
            val inc=ReceiverIncarnation("new-glasses",2); val c=r.engine.journal.current!!
            r.codec(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
                presentation=PresentationReport(c.id,b,c.wakeRevision,inc,1,ReceiverUsabilityMode.PROJECTED)))
            check(r.engine.ready)
        },
        "C1 mapped T cancellation writes whole-H tombstone in the same journal transition" to {
            val r=Rig();r.request();val h=r.engine.journal.current!!.id
            val cuts=mutableListOf<ActivationJournal>()
            val engine=PhoneActivationEngine(r.core,r.store::read,{ value -> cuts.add(value);r.store.write(value) },{null},{r.context})
            engine.command(SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T","R")))
            val first=cuts.first {it.triggers["T"]!!.canceled}
            check(h in first.canceled && first.current?.desired==false)
            val restarted=PhoneActivationEngine(r.core,{first},{},{null},{r.context})
            restarted.tick();check(!restarted.desired)
        },
        "pure core snapshot neither persists nor drives cancellation" to {
            var writes=0; var stops=0
            val c=SessionController({}, {stops++},initialPhase=SessionPhase.UNKNOWN,retainCorrelation={writes++})
            val before=writes; repeat(4) { check(!c.ownership().released) }
            check(writes==before && stops==0)
        },
        "setup remains pending and retries only after actual old release" to {
            val r=Rig(); r.setup="AUTHORIZATION_REQUIRED"; r.request()
            check(r.engine.desired && r.starts.isEmpty())
            r.setup=null; r.engine.tick(); val a=r.owner()
            r.core.backend(a,"FAILED"); repeat(3) { r.engine.tick() }
            check(r.starts.size==1 && !r.core.ownership().released)
            r.release(); check(r.starts.size==2)
        },
        "PROJECTED D reuses H/A while current local panel stays input-usable and weak HID cannot downgrade" to {
            val r=Rig();r.request();val a=r.owner()
            r.core.backend(a,"READY");r.context=ProjectionContext(a,"local-panel-token");r.engine.tick()
            r.presentMode(1,ReceiverUsabilityMode.LOCAL_PANEL);check(r.engine.ready && r.engine.inputUsable)
            val h=r.engine.handle!!;val before=r.engine.journal.current!!.wakeRevision
            r.engine.request(ActivationCause.NOTIFICATION,h.id,"D1");r.engine.tick()
            val d=r.engine.journal.current!!
            check(d.id==h.id && d.attempt==a && d.goal==ActivationGoal.PROJECTED && d.demandId=="D1" && d.wakeRevision==before+1)
            check(r.engine.inputUsable && !r.engine.ready && r.starts.size==1)
            r.engine.request(ActivationCause.HID_PRESS,h.id);check(r.engine.journal.current==d)
            r.presentMode(2,ReceiverUsabilityMode.LOCAL_PANEL);check(r.engine.inputUsable && !r.engine.ready)
            r.presentMode(3,ReceiverUsabilityMode.PROJECTED,wake=before);check(!r.engine.ready)
            r.presentMode(4,ReceiverUsabilityMode.PROJECTED);check(r.engine.ready && r.engine.inputUsable)
            r.engine.request(ActivationCause.NOTIFICATION,h.id,"D2");r.engine.tick()
            val current=r.engine.journal.current!!
            r.presentMode(5,ReceiverUsabilityMode.NONE,wake=current.wakeRevision-1)
            check(!r.engine.inputUsable && !r.engine.ready)
            r.engine.request(ActivationCause.HID_ATTACHMENT,h.id);check(r.engine.journal.current==current)
        },
        "original D survives actual A release and B retry with typed projected completion" to {
            val r=Rig();r.request();val a=r.owner();val h=r.engine.handle!!
            r.engine.request(ActivationCause.NOTIFICATION,h.id,"original-D");r.engine.tick()
            r.core.backend(a,"FAILED","fixture failure");r.engine.tick();check(r.starts.size==1)
            r.core.backend(a,"STOPPED");r.engine.tick();val b=r.owner();check(b!=a && r.starts.size==2)
            check(r.engine.journal.current!!.demandId=="original-D")
            r.core.backend(b,"READY");r.context=ProjectionContext(b,"retry-context");r.engine.tick()
            r.presentMode(1,ReceiverUsabilityMode.LOCAL_PANEL);check(!r.engine.ready)
            r.presentMode(2,ReceiverUsabilityMode.PROJECTED);check(r.engine.ready && r.engine.journal.current!!.demandId=="original-D")
        },
        "presentation revisions incarnation and empty context invalidate observed Ready" to {
            val r=Rig(); r.request(); val a=r.owner(); r.core.backend(a,"READY")
            r.context=ProjectionContext(a,"private-local-token"); r.engine.tick()
            r.present(1,true); check(r.engine.ready)
            r.present(3,false); r.present(2,true); check(!r.engine.ready)
            r.present(4,true); check(r.engine.ready)
            r.context=ProjectionContext(); r.engine.tick(); check(!r.engine.ready)
            r.context=ProjectionContext(a,"private-local-token"); r.engine.tick(); check(!r.engine.ready)
            val newer=ReceiverIncarnation("receiver-two",2)
            r.present(1,true,newer); check(r.engine.ready)
            r.present(999,false); check(r.engine.ready)
            val wire=r.codec(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=newer))
            check(!wire.toString().contains("private-local-token"))
        },
        "task reconciliation retains original AppTask scope through reuse errors and cold restart" to {
            val first=ReceiverTaskBinding(ReceiverLaunchScope("first","T1",7),"H1")
            val second=ReceiverTaskBinding(ReceiverLaunchScope("second","T2",7),"H2")
            var old=ReceiverTaskRegistry.Presence.PRESENT
            val registry=ReceiverTaskRegistry()
            registry.register(first) { old }; registry.register(first) { ReceiverTaskRegistry.Presence.ABSENT }
            registry.register(second) { ReceiverTaskRegistry.Presence.PRESENT }
            check(registry.reconcile().isEmpty()) // Recreation cannot replace original task query.
            old=ReceiverTaskRegistry.Presence.ABSENT
            check(registry.reconcile()==listOf(first)); check(registry.reconcile()==listOf(first))
            registry.acknowledged(first); check(registry.reconcile().isEmpty())
            val errors=ReceiverTaskRegistry(); errors.register(first) { throw IOException("unknown") }
            check(errors.reconcile().isEmpty())
            val cold=ReceiverTaskRegistry(listOf(first,second))
            check(cold.reconcile().isEmpty() && cold.unresolved().size==2)
            cold.register(second) { ReceiverTaskRegistry.Presence.PRESENT }
            check(cold.unresolved()==listOf(first)) // Matching numeric ID never reattaches first.
        },
    )
    val failures=mutableListOf<String>()
    tests.forEach { (name,test) -> try { test(); println("PASS: $name") } catch(t:Throwable) { failures.add(name); println("FAIL: $name"); t.printStackTrace() } }
    check(failures.isEmpty()) { failures.joinToString() }
    println("PASS: ${tests.size} shared activation actual-core/codec/journal scenarios; platform boundaries explicit")
}
