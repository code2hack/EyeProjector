package com.code2hack.eyeprojector.directhost.notifications
import org.junit.runner.JUnitCore
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import kotlin.system.exitProcess
fun main() {
    val failed = mutableSetOf<Description>()
    val junit = JUnitCore()
    junit.addListener(object : RunListener() {
        override fun testFailure(failure: Failure) {
            failed.add(failure.description)
            println("FAIL ${failure.description.methodName}: ${failure.message}")
            failure.exception.printStackTrace(System.out)
        }
        override fun testFinished(description: Description) {
            if (description !in failed) println("PASS ${description.methodName}")
        }
    })
    val result = junit.run(NotificationEventsTest::class.java, NotificationIngressRuntimeTest::class.java, NotificationListenerAdapterTest::class.java)
    println("RESULT tests=${result.runCount} failures=${result.failureCount} ignored=${result.ignoreCount}")
    exitProcess(if (result.wasSuccessful()) 0 else 1)
}
