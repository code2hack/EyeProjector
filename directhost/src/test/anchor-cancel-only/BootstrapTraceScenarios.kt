package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.content.ApplicationInfo
import android.content.Assets
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket

private fun awaitTrace(predicate:()->Boolean) {
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
    while(!predicate()&&System.nanoTime()<until)Thread.yield()
    check(predicate()) {"original observer did not finish its explicit obligations"}
}
private fun traceOf(session:Any):PhoneInputTrace?=session.javaClass.getDeclaredField("inputTrace").apply{isAccessible=true}.get(session) as PhoneInputTrace?
private fun installTrace(f:Fixture,config:PhoneInputTraceConfig?) {
    f.type.getDeclaredMethod("installInputTraceConfiguration",PhoneInputTraceConfig::class.java).apply{isAccessible=true}.invoke(f.session,config)
}
internal fun bootstrapTraceScenarios(run:(String,()->Unit)->Unit) {
    run("normal-START-configuration-real-owned-preparation-and-Bridge-Session-factory-install-default-off-and-enabled-observer") {
        val root=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"bootstrap-normal").apply{mkdirs()}
        val context=Context(root)
        context.applicationInfo=ApplicationInfo(sourceDir=File(root,"fixture.apk").apply{writeText("host APK attribution boundary")}.path)
        val inputs=File(System.getenv("EYEPROJECTOR_TRACE_FIXTURES"))
        val session=File(root,"session").apply{mkdirs()}
        for(name in listOf("identity.p12","password","peer.crt"))File(inputs,name).copyTo(File(session,name),overwrite=true)
        File(session,"endpoint.properties").writeText("host=192.168.42.1\nport=44378\nprivate.payload=preserved\n")
        val asset=File(System.getenv("EYEPROJECTOR_TRACE_SERVER"))
        Assets.openForTest={name->check(name=="scrcpy-server-v5.0.1");asset.inputStream()}
        try {
            for(enabled in listOf(false,true)) {
                val prepared=AtomicReference<Bridge?>();val listener=Listener()
                val service=ShizukuHostService(context,OwnedShellSessions(File(root,"owned-$enabled"))) { files,owner,report->
                    // Real public-constructor preparation; replace only the external listener before run/bind.
                    val bridge=ShizukuHostService::class.java.getDeclaredMethod("prepareOwnedBridge",BootstrapSessionFiles::class.java,String::class.java,kotlin.jvm.functions.Function1::class.java)
                        .apply{isAccessible=true}.invoke(serviceForPreparation,files,owner,report) as Bridge
                    val field=Bridge::class.java.getDeclaredField("listener").apply{isAccessible=true}
                    (field.get(bridge) as SSLServerSocket).close();field.set(bridge,listener)
                    prepared.set(bridge);bridge
                }
                serviceForPreparation=service
                val start=BootstrapTraceWiring(context,service)
                val path=File(root,"enabled-$enabled.jsonl")
                val original=File(session,"endpoint.properties").readBytes()
                val prior=start.configureInputTrace(if(enabled)PhoneInputTraceConfig(path.path,"normal-start")else null)
                check(prior==null)
                try {
                    start.start("trace-bootstrap-$enabled")
                    check(start.failure==null&&listener.entered.await(3,TimeUnit.SECONDS))
                    val bridge=checkNotNull(prepared.get())
                    val bytes=File(session,"endpoint.properties").readBytes();check(bytes.contentEquals(original))
                    val directory=Bridge::class.java.getDeclaredField("directory").apply{isAccessible=true}.get(bridge) as File
                    val properties=java.util.Properties().apply{File(directory,"endpoint.properties").inputStream().use{load(it)}}
                    check(properties.getProperty("private.payload")=="preserved"&&properties.getProperty("camera.source")=="phone")
                    repeat(if(enabled)3 else 1) { index ->
                        val peer=Peer()
                        val actual=Bridge::class.java.getDeclaredMethod("createSession",javax.net.ssl.SSLSocket::class.java,DataInputStream::class.java)
                            .apply{isAccessible=true}.invoke(bridge,peer,DataInputStream(peer.inputStream))
                        val recorder=traceOf(actual)
                        try {
                            check((recorder!=null)==enabled){"normal START configuration did not reach actual Session recorder"}
                            val status=actual.javaClass.getDeclaredMethod("managedProjectionStatus").apply{isAccessible=true}.invoke(actual).toString()
                            check(status.contains("\"enabled\":$enabled"))
                            actual.javaClass.getDeclaredMethod("receiveControl",com.code2hack.eyeprojector.protocol.Frame::class.java).apply{isAccessible=true}
                                .invoke(actual,com.code2hack.eyeprojector.protocol.Frame(com.code2hack.eyeprojector.protocol.Wire.PING))
                        } finally {actual.javaClass.getDeclaredMethod("close").apply{isAccessible=true}.invoke(actual)}
                        if(recorder!=null) {awaitTrace{recorder.terminated&&traceOf(actual)==null}}
                    }
                    if(enabled)check(path.readText().contains("normal-start")&&path.readText().contains("FRAME_EXIT"))else check(!path.exists())
                } finally {start.configureInputTrace(prior);service.stop()}
            }
        } finally { Assets.openForTest=null;serviceForPreparation=null }
    }
    run("observer-close-drains-original-frame-that-finishes-after-close-without-joining-caller") {
        val path=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"late-observer.jsonl")
        val trace=PhoneInputTrace.create(path,ProjectionContext("late-owner","late-session"),601)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Thread{trace.withFrame(com.code2hack.eyeprojector.protocol.Frame(com.code2hack.eyeprojector.protocol.Wire.PING)){entered.countDown();check(release.await(4,TimeUnit.SECONDS));trace.record("LATE_ORIGINAL")}}
        worker.start()
        try {check(entered.await(2,TimeUnit.SECONDS));trace.close();check(!trace.terminated)}finally{release.countDown();worker.join(3000)}
        check(!worker.isAlive);awaitTrace{trace.terminated}
        check(path.readText().contains("LATE_ORIGINAL")&&path.readText().contains("FRAME_EXIT"))
    }
    run("actual-Session-bounded-held-release-preserves-late-original-transport-obligation") {
        Fixture().use { f ->
            val path=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"session-late-release.jsonl")
            installTrace(f,PhoneInputTraceConfig(path.path,"late-transport"))
            val trace=checkNotNull(traceOf(f.session))
            val input=f.get("inputControls") as com.code2hack.eyeprojector.directhost.anchor.execution.AnchorSessionInput
            val geometry=com.code2hack.eyeprojector.protocol.Geometry(480,640)
            input.geometry(geometry)
            input.touch(com.code2hack.eyeprojector.protocol.Touch(com.code2hack.eyeprojector.protocol.Touch.Phase.DOWN,65,com.code2hack.eyeprojector.protocol.Tap(10,20,geometry)))
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
            val written=ByteArrayOutputStream()
            f.control.sink=object:OutputStream(){
                override fun write(value:Int)=write(byteArrayOf(value.toByte()))
                override fun write(bytes:ByteArray,offset:Int,length:Int){
                    entered.countDown();while(release.count>0)try{release.await()}catch(_:InterruptedException){}
                    written.write(bytes,offset,length);trace.record("ORIGINAL_TRANSPORT_WRITE_FINISHED");finished.countDown()
                }
            }
            try {f.closeSession();check(entered.await(2,TimeUnit.SECONDS));check(!trace.terminated)}finally{release.countDown()}
            check(finished.await(2,TimeUnit.SECONDS));awaitTrace{trace.terminated&&traceOf(f.session)==null}
            check(written.size()>0&&path.readText().contains("ORIGINAL_TRANSPORT_WRITE_FINISHED"))
        }
    }
    run("actual-Session-close-retains-observer-only-through-original-Function-work-completion") {
        Fixture().use { f ->
            val path=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"session-late-work.jsonl")
            installTrace(f,PhoneInputTraceConfig(path.path,"late-work"))
            val trace=checkNotNull(traceOf(f.session)){"actual Session recorder absent"}
            val work=f.get("functionWork") as com.code2hack.eyeprojector.directhost.input.FunctionWork
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val ended=CountDownLatch(1)
            work.submit {entered.countDown();while(release.count>0)try{release.await()}catch(_:InterruptedException){};trace.record("ORIGINAL_WORK_FINISHED");ended.countDown()}
            try {check(entered.await(2,TimeUnit.SECONDS));f.closeSession();check(!trace.terminated)}finally{release.countDown()}
            check(ended.await(2,TimeUnit.SECONDS));awaitTrace{trace.terminated&&traceOf(f.session)==null}
            check(path.readText().contains("ORIGINAL_WORK_FINISHED"))
        }
    }
}
private var serviceForPreparation:ShizukuHostService?=null
