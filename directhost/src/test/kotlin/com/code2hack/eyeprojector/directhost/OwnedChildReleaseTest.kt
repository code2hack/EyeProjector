package com.code2hack.eyeprojector.directhost

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OwnedChildReleaseTest {
    private class Child : java.lang.Process() {
        val calls = ArrayList<String>()
        val budgets = ArrayList<Long>()
        var alive = true
        var waiting: (Int) -> Boolean = { false }
        var destroying: () -> Unit = {}
        var forcing: () -> Unit = {}
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("Unbounded wait must not be used")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            calls.add("wait"); budgets.add(unit.toNanos(timeout))
            return waiting(budgets.size).also { if (it) alive = false }
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive() = alive
        override fun destroy() { calls.add("destroy"); destroying() }
        override fun destroyForcibly(): java.lang.Process { calls.add("force"); forcing(); return this }
    }

    @Test fun preexistingInterruptionCannotSkipEscalationAndIsRestored() {
        val child = Child().apply {
            waiting = { attempt -> assertFalse(Thread.currentThread().isInterrupted); attempt == 3 }
        }
        Thread.currentThread().interrupt()
        try {
            val observed = OwnedChildRelease.release(child, 100) { 0 }
            assertTrue(observed.initiallyInterrupted)
            assertEquals(listOf("wait", "destroy", "wait", "force", "wait"), child.calls)
            assertFalse(child.isAlive)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun repeatedMidWaitInterruptionsSpendTheOriginalPhaseBudget() {
        var now = 0L
        val child = Child().apply {
            waiting = { attempt ->
                when (attempt) {
                    1, 2 -> { now += 60; throw InterruptedException() }
                    3 -> { now += 100; false }
                    else -> true
                }
            }
        }
        try {
            val observed = OwnedChildRelease.release(child, 100) { now }
            assertFalse(observed.initiallyInterrupted)
            assertEquals(listOf(100L, 40L, 100L, 100L), child.budgets)
            assertEquals(2, observed.steps.first().interruptions)
            assertEquals(120L, observed.steps.first().elapsedNanos)
            assertEquals(listOf("initialWait", "destroy", "destroyWait", "force", "forceWait"), observed.steps.map { it.stage })
            assertFalse(child.isAlive)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun waitAndTerminationFailuresRemainDistinctAndCannotSuppressForce() {
        val initialFailure = IllegalStateException("initial wait")
        val destroyFailure = IllegalStateException("destroy")
        val destroyWaitFailure = IllegalStateException("destroy wait")
        val forceFailure = IllegalStateException("force")
        val child = Child().apply {
            waiting = { attempt -> when (attempt) {
                1 -> throw initialFailure
                2 -> throw destroyWaitFailure
                else -> false
            } }
            destroying = { throw destroyFailure }
            forcing = { throw forceFailure }
        }
        val observed = OwnedChildRelease.release(child, 100) { 0 }
        assertEquals(listOf("wait", "destroy", "wait", "force", "wait"), child.calls)
        assertEquals(listOf(initialFailure, destroyFailure, destroyWaitFailure, forceFailure, null), observed.steps.map { it.failure })
        assertTrue("Caller must still enforce incomplete release", child.isAlive)
        child.waiting = { true }
        val retry = OwnedChildRelease.release(child, 100) { 0 }
        assertEquals(listOf("initialWait"), retry.steps.map { it.stage })
        assertFalse(child.isAlive)
    }

    @Test fun naturalExitDoesNotTerminateTheChild() {
        val child = Child().apply { waiting = { true } }
        val observed = OwnedChildRelease.release(child, 100) { 0 }
        assertEquals(listOf("wait"), child.calls)
        assertTrue(observed.steps.single().returned)
        assertFalse(child.isAlive)
    }
}
