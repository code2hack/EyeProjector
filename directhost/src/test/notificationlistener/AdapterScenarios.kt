package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Button
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.apps.ChosenApps
import org.junit.Assert.*
import org.junit.Test

/** Actual service/helper/panel/runtime against explicit framework/chosen-storage boundaries. */
class NotificationListenerAdapterTest {
    private val app = "example.chosen"
    private fun notification(time: Long = 10) = StatusBarNotification(app, "synthetic-key", time)
    private fun awaitBaseline(owner: PhoneNotificationRuntime) = Looper.until {
        owner.status().baseline != NotificationListenerBaseline.INITIALIZING
    }

    @Test fun actualServiceOnlyExtractsChosenMetadataAndPostedRemovedAreOnce() {
        ChosenApps.selected = setOf(app)
        val service = PhoneNotificationListener()
        val owner = PhoneNotificationRuntime.get(service.applicationContext)
        val observed = mutableListOf<NotificationIngressObservation>()
        val subscription = owner.observeEvents { observed.add(it) }
        try {
            service.onListenerConnected(); awaitBaseline(owner)
            service.onNotificationPosted(notification())
            service.onNotificationRemoved(notification())
            assertEquals(listOf(NotificationEventKind.NEW_RECORD, NotificationEventKind.REMOVED_CURRENT), observed.map { it.event.kind })
            service.onNotificationPosted(object : StatusBarNotification("example.nonchosen", "unused", 0) {
                override val key: String get() = error("nonchosen key accessed")
                override val postTime: Long get() = error("nonchosen timestamp accessed")
            })
            assertEquals(2, observed.size)
        } finally { subscription.close(); service.onDestroy() }
    }

    @Test fun actualServiceBaselineHandlesReturnedNullAndExceptionAsObservedUnavailable() {
        for (throws in listOf(false, true)) {
            val service = PhoneNotificationListener()
            val owner = PhoneNotificationRuntime.get(service.applicationContext)
            service.suppliedSnapshot = null
            service.snapshotFailure = throws
            try {
                service.onListenerConnected(); awaitBaseline(owner)
                assertEquals(NotificationListenerBaseline.UNAVAILABLE, owner.status().baseline)
                assertEquals(1, service.queryCount)
            } finally { service.onDestroy() }
        }
    }

    @Test fun actualServiceSnapshotFiltersPackageBeforePrivateMetadataAndReconnectionDoesNotReplay() {
        val service = PhoneNotificationListener()
        val owner = PhoneNotificationRuntime.get(service.applicationContext)
        service.suppliedSnapshot = arrayOf(notification(), object : StatusBarNotification("example.other", "unused", 0) {
            override val key: String get() = error("unrelated snapshot key accessed")
            override val postTime: Long get() = error("unrelated snapshot timestamp accessed")
        })
        try {
            service.onListenerConnected(); awaitBaseline(owner)
            val seen = mutableListOf<NotificationIngressObservation>()
            val subscription = owner.observeEvents { seen.add(it) }
            service.onNotificationPosted(notification())
            assertEquals(NotificationEventKind.DUPLICATE_OR_REPLAY, seen.single().event.kind)
            subscription.close()
        } finally { service.onDestroy() }
    }

    @Test fun oldActualServiceTeardownCannotDisconnectItsReplacement() {
        val old = PhoneNotificationListener()
        val replacement = PhoneNotificationListener()
        val owner = PhoneNotificationRuntime.get(old.applicationContext)
        try {
            old.onListenerConnected(); awaitBaseline(owner)
            replacement.onListenerConnected(); awaitBaseline(owner)
            old.onListenerDisconnected(); old.onUnbind(null); old.onDestroy()
            assertTrue(owner.status().connected)
            val seen = mutableListOf<NotificationIngressObservation>()
            val registration = owner.observeEvents { seen.add(it) }
            old.onNotificationPosted(notification())
            replacement.onNotificationPosted(notification())
            assertEquals(1, seen.size)
            registration.close()
        } finally { old.onDestroy(); replacement.onDestroy() }
    }

    @Test fun ordinarySettingsDetailHasFlattenedComponentAndNewTaskFallbackIsTruthful() {
        val context = Context()
        assertEquals(NotificationAccessApproval.DENIED, NotificationAccess.approval(context))
        assertTrue(NotificationAccess.openSettings(context))
        val detail = context.activities.single()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, detail.action)
        assertEquals(context.packageName + "/" + PhoneNotificationListener::class.java.name,
            detail.extras[Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME])
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, detail.flags)
        assertEquals(NotificationAccessApproval.DENIED, NotificationAccess.approval(context))
        context.activities.clear(); context.failingActions.add(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        assertTrue(NotificationAccess.openSettings(context))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, context.activities.last().action)
        context.failingActions.add(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        assertFalse(NotificationAccess.openSettings(context))
        context.notifications.fail = true
        assertEquals(NotificationAccessApproval.UNKNOWN, NotificationAccess.approval(context))
    }

    @Test fun actualPanelRendersIndependentFactsAndClosingDoesNotDisconnectService() {
        val service = PhoneNotificationListener()
        val owner = PhoneNotificationRuntime.get(service.applicationContext)
        val context = service.applicationContext
        context.notifications.granted = false
        val panel = NotificationAccessPanel(context)
        try {
            service.onListenerConnected(); awaitBaseline(owner)
            panel.onAttachedToWindow()
            val texts = panel.children.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.any { it.contains("Notification access: Off") && it.contains("Listener: connected") })
            context.failingActions.add(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            context.failingActions.add(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            panel.children.filterIsInstance<Button>().first().performClick()
            assertTrue(panel.children.filterIsInstance<TextView>().any { it.text.toString().contains("could not be opened") })
            panel.onDetachedFromWindow()
            assertTrue(owner.status().connected)
        } finally { panel.close(); service.onDestroy(); context.failingActions.clear() }
    }
}
