package com.code2hack.eyeprojector.directhost
import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.activation.PhoneActivationEngine
import java.util.concurrent.*

/** Only facade dependencies: real command body, real feature FIFO/engine and real Anchor gate. */
internal class FacadeCommand(engine:PhoneActivationEngine,private val admit:()->Boolean):AutoCloseable {
    private lateinit var activation:PhoneActivationEngine
    init { activation=engine }
    private val featureLane=Executors.newSingleThreadExecutor()
    private fun userControl()=admit()
    private fun state()=activation.state()
    private fun drain(request:SessionControl.Request):SessionState?=activation.command(request)
    fun receive(request:SessionControl.Request)=command(request)
    // ACTUAL_COMMAND
    override fun close(){featureLane.shutdown();check(featureLane.awaitTermination(3,TimeUnit.SECONDS))}
}
