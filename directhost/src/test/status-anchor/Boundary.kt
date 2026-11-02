package com.code2hack.eyeprojector.directhost
import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.activation.*
import java.util.concurrent.*

/** Only facade dependencies: real command body, real feature FIFO/engine and real Anchor gate. */
internal class FacadeCommand(engine:PhoneActivationEngine,private val admit:()->Boolean,private val capture:()->()->Boolean={admit}):AutoCloseable {
    private lateinit var activation:PhoneActivationEngine
    init { activation=engine }
    private val featureLane=Executors.newSingleThreadExecutor()
    private fun userControl()=admit()
    private fun state()=activation.state()
    private val remoteControls=ConcurrentHashMap<RemoteControlKey,RemoteControlReceipt>()
    private fun captureRemoteControl(request:SessionControl.Request)=RemoteControlReceipt(request,capture())
    private fun drain(request:SessionControl.Request,receipt:RemoteControlReceipt?=null):SessionState? =
        if(remoteControlConsumed(request,receipt))activation.consumeRemoteControl(receipt?.request?:request) else activation.command(request)
    // ACTUAL_CONTROL_DECISION
    fun receive(request:SessionControl.Request)=command(request)
    fun blockWriter(entered:CountDownLatch,release:CountDownLatch){featureLane.execute{entered.countDown();check(release.await(3,TimeUnit.SECONDS))}}
    // ACTUAL_COMMAND
    override fun close(){featureLane.shutdown();check(featureLane.awaitTermination(3,TimeUnit.SECONDS))}
}
