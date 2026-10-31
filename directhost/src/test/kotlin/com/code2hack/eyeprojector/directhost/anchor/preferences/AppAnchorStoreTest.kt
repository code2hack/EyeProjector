package com.code2hack.eyeprojector.directhost.anchor.preferences

import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.apps.*
import com.code2hack.eyeprojector.directhost.notifications.*
import com.code2hack.eyeprojector.directhost.scale.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual codec/store and atomic local-file persistence; these are host checks, not Android input. */
class AppAnchorStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app = "example.app"
    private val other = "example.other"
    private fun anchor(packageName: String = app, mode: AnchorMode = AnchorMode.CLICK,
        activity: String? = "example.app/.Current", resource: String = "example.app:id/action") =
        AppAnchor(AnchorTargetDescriptor(packageName, activity, 1, resource, "android.widget.Button"), mode)
    private fun directory() = File(temporary.root, "anchors")
    private fun store(directory: File = directory()) = AppAnchorStore(FileAppAnchorStorage(directory))

    @Test fun absentPreferenceDoesNotCreateFilesOrPickDefaultMode() {
        assertNull(store().read(app))
        assertFalse(directory().exists())
    }

    @Test fun bothExplicitModesAndNullableActivitySurviveNewStorageInstances() {
        for (mode in AnchorMode.entries) {
            val value = anchor(mode = mode, activity = null, resource = "example.app:id/操作")
            store().replace(value)
            assertEquals(value, store().read(app))
            assertEquals(mode, store().read(app)!!.mode)
        }
    }

    @Test fun activityChangeReplacesSamePackageInsteadOfAddingAnotherAnchor() {
        val old = anchor(mode = AnchorMode.HOLD)
        val next = anchor(activity = "example.app/.Other", resource = "example.app:id/next")
        store().replace(old)
        store().replace(next)
        assertEquals(next, store().read(app))
        assertEquals(1, directory().listFiles()!!.size)
    }

    @Test fun clearDeletesOnlyThatPackageAndIsPersistentAndIdempotent() {
        val events = mutableListOf<AppAnchorChange>()
        val store = store()
        store.replace(anchor())
        val retained = anchor(other, AnchorMode.HOLD)
        store.replace(retained)
        store.observe(app, events::add)
        store.clear(app)
        store.clear(app)
        assertNull(store().read(app))
        assertEquals(retained, store().read(other))
        assertEquals(listOf(AppAnchorChange.Cleared(anchor())), events)
        assertEquals(1, directory().listFiles()!!.size)
    }

    @Test fun typedChangesContainCompletePreviousAndCurrentValues() {
        val store = store()
        val events = mutableListOf<AppAnchorChange>()
        store.observe(app, events::add)
        val click = anchor()
        val hold = anchor(mode = AnchorMode.HOLD)
        store.replace(click)
        store.replace(hold)
        store.clear(app)
        assertEquals(listOf(AppAnchorChange.Replaced(null, click), AppAnchorChange.Replaced(click, hold),
            AppAnchorChange.Cleared(hold)), events)
    }

    @Test fun callbacksSeeCommittedDiskAndInstanceValueBeforeOperationReturns() {
        val store = store()
        val order = mutableListOf<String>()
        store.observe(app) { change ->
            val expected = (change as? AppAnchorChange.Replaced)?.current
            assertEquals(expected, store.read(app))
            assertEquals(expected, store().read(app))
            order.add("callback")
        }
        store.replace(anchor())
        order.add("returned")
        store.clear(app)
        order.add("returned")
        assertEquals(listOf("callback", "returned", "callback", "returned"), order)
    }

    @Test fun subscriptionsArePackageFilteredAndCloseStopsDelivery() {
        val store = store()
        val events = mutableListOf<AppAnchorChange>()
        val subscription = store.observe(app, events::add)
        store.replace(anchor(other))
        store.replace(anchor())
        subscription.close()
        subscription.close()
        store.clear(app)
        assertEquals(listOf(AppAnchorChange.Replaced(null, anchor())), events)
    }

    @Test fun duplicateCallbacksHaveIndependentSubscriptionLifetimes() {
        val store = store()
        var calls = 0
        val callback: (AppAnchorChange) -> Unit = { calls++ }
        val first = store.observe(app, callback)
        val second = store.observe(app, callback)
        second.close()
        second.close()
        store.replace(anchor())
        assertEquals(1, calls)
        first.close()
        store.clear(app)
        assertEquals(1, calls)
    }

    @Test fun closingAnotherSubscriptionInCallbackPreventsItsDelivery() {
        val store = store()
        var calls = 0
        lateinit var second: java.io.Closeable
        store.observe(app) { second.close() }
        second = store.observe(app) { calls++ }
        store.replace(anchor())
        assertEquals(0, calls)
    }

    @Test fun restartAndSubscriptionDoNotReplayEventsButExplicitSameValueReplacementDoes() {
        store().replace(anchor())
        val reopened = store()
        val events = mutableListOf<AppAnchorChange>()
        reopened.observe(app, events::add)
        assertEquals(anchor(), reopened.read(app))
        assertTrue(events.isEmpty())
        reopened.replace(anchor())
        assertEquals(listOf(AppAnchorChange.Replaced(anchor(), anchor())), events)
    }

    @Test fun failedReplacementOrClearDoesNotNotifyOrChangePriorDiskValue() {
        val disk = FileAppAnchorStorage(directory())
        store().replace(anchor())
        val failures = object : AppAnchorStorage {
            override fun read(packageName: String) = disk.read(packageName)
            override fun write(packageName: String, anchor: AppAnchor?) { throw IOException("test storage failure") }
        }
        val failing = AppAnchorStore(failures)
        val events = mutableListOf<AppAnchorChange>()
        failing.observe(app, events::add)
        assertThrows(IOException::class.java) { failing.replace(anchor(mode = AnchorMode.HOLD)) }
        assertThrows(IOException::class.java) { failing.clear(app) }
        assertEquals(anchor(), store().read(app))
        assertTrue(events.isEmpty())
    }

    @Test fun incompleteRegistrationMakesNoStoreCallAndIncompleteEncodedRecordIsNotAReplacement() {
        val original = anchor(mode = AnchorMode.HOLD)
        val store = store()
        store.replace(original)
        val events = mutableListOf<AppAnchorChange>()
        store.observe(app, events::add)
        // Registration owns the tentative descriptor/chooser. Neither holding that value nor
        // canceling it calls replace; a complete, explicitly selected mode is required by the API.
        val tentativeDescriptor = anchor(resource = "example.app:id/replacement").descriptor
        assertNotEquals(original.descriptor, tentativeDescriptor)
        val incomplete = AppAnchorCodec.encode(anchor()).dropLast(3).toByteArray()
        assertThrows(Exception::class.java) { AppAnchorCodec.decode(incomplete) }
        assertEquals(original, store().read(app))
        assertTrue(events.isEmpty())
    }

    @Test fun leftoverUncommittedFileDoesNotReplaceSavedRecord() {
        store().replace(anchor())
        File(directory(), "anchor-interrupted.pending").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(anchor(), store().read(app))
    }

    @Test fun observerFailureIsReportedWithoutUndoingCommitOrStarvingOtherObservers() {
        val failures = mutableListOf<RuntimeException>()
        val store = AppAnchorStore(FileAppAnchorStorage(directory()), failures::add)
        val events = mutableListOf<AppAnchorChange>()
        val failure = IllegalStateException("test consumer failure")
        store.observe(app) { throw failure }
        store.observe(app, events::add)
        store.replace(anchor())
        assertEquals(anchor(), store().read(app))
        assertEquals(listOf(failure), failures)
        assertEquals(listOf(AppAnchorChange.Replaced(null, anchor())), events)
    }

    @Test fun otherThreadCannotReadReplacementUntilSynchronousCallbacksFinish() {
        val store = store()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        store.observe(app) {
            callbackEntered.countDown()
            assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
        }
        try {
            val writer = executor.submit { store.replace(anchor()) }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            val reader = executor.submit<AppAnchor?> { readerStarted.countDown(); store.read(app) }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { reader.get(100, TimeUnit.MILLISECONDS) }
            releaseCallback.countDown()
            writer.get(5, TimeUnit.SECONDS)
            assertEquals(anchor(), reader.get(5, TimeUnit.SECONDS))
        } finally { releaseCallback.countDown(); executor.shutdownNow() }
    }

    @Test fun concurrentDiskReaderSeesOnlyWholeOldOrNewRecords() {
        val old = anchor(mode = AnchorMode.HOLD)
        val next = anchor(activity = "example.app/.Other", resource = "example.app:id/different")
        val writer = store()
        writer.replace(old)
        val reader = store()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val result = executor.submit {
                started.countDown()
                repeat(300) { assertTrue(reader.read(app) in listOf(old, next)) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(30) { writer.replace(if (it % 2 == 0) next else old) }
            result.get(5, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    @Test fun removingAndReaddingChosenAppRetainsAnchorScaleAndNotificationPreferences() {
        var chosen = emptyList<ChosenApp>()
        val selection = ChosenAppStore(object : ChosenAppStorage {
            override fun read() = chosen
            override fun write(apps: List<ChosenApp>) { chosen = apps }
        })
        val scaleValues = mutableMapOf<String, Double>()
        val scales = AppScaleStore(object : AppScaleStorage {
            override fun read(packageName: String) = scaleValues[packageName]
            override fun write(packageName: String, scale: Double): Boolean { scaleValues[packageName] = scale; return true }
        })
        val notificationValues = mutableMapOf<String, Boolean>()
        val notifications = AppNotificationStore(object : AppNotificationStorage {
            override fun read(packageName: String) = notificationValues[packageName]
            override fun write(packageName: String, enabled: Boolean) { notificationValues[packageName] = enabled }
        })
        val anchors = store()
        anchors.replace(anchor(mode = AnchorMode.HOLD))
        selection.add(InstalledApp(app, "App"))
        assertTrue(scales.write(app, 1.25))
        notifications.setEnabled(app, false)
        selection.remove(app)
        assertTrue(selection.read().isEmpty())
        assertEquals(anchor(mode = AnchorMode.HOLD), store().read(app))
        assertEquals(1.25, scales.read(app), 0.0)
        assertFalse(notifications.isEnabled(app))
        selection.add(InstalledApp(app, "New label"))
        assertEquals(anchor(mode = AnchorMode.HOLD), store().read(app))
        anchors.clear(app)
        assertEquals(listOf(ChosenApp(app, "New label")), selection.read())
        assertEquals(1.25, scales.read(app), 0.0)
        assertFalse(notifications.isEnabled(app))
    }
}
