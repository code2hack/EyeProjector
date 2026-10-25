package android.app

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.service.notification.StatusBarNotification

open class Instrumentation {
    val statuses = mutableListOf<Map<String, String>>()
    var mainCalls = 0
    var unhandledMainFailures = 0
    var failNextMain = false
    var failedMainCalls = 0
    var afterNextMain: (() -> Unit)? = null
    var onStatus: ((Bundle) -> Unit)? = null
    fun runOnMainSync(work: Runnable) {
        mainCalls++
        if (failNextMain) {
            failNextMain = false
            failedMainCalls++
            // Explicit boundary fault BEFORE the Runnable, not an ordinary renderer throw.
            throw IllegalStateException("PRIVATE_EXCEPTION_SENTINEL_NEVER_REPORT")
        }
        // Android does not supply automatic exception transport from a main Runnable.
        // Count that hazardous boundary without hanging/crashing this isolated host JVM.
        Looper.onMain {
            try { work.run() } catch (_: Throwable) { unhandledMainFailures++ }
            val after = afterNextMain
            afterNextMain = null
            after?.invoke()
        }
    }
    fun sendStatus(code: Int, bundle: Bundle) { statuses.add(bundle.snapshot()); onStatus?.invoke(bundle) }
}

class Notification private constructor(val channelId: String, val smallIcon: Int) {
    class Builder(private val context: Context, private val channelId: String) {
        private var smallIcon = 0
        fun setSmallIcon(icon: Int): Builder = apply { smallIcon = icon }
        fun build(): Notification { check(smallIcon != 0); return Notification(channelId, smallIcon) }
    }
}
class NotificationChannel(val id: String, val name: CharSequence, var importance: Int)

/** App-owned NotificationManager boundary. onNotify is an explicit host simulation of delivery. */
class NotificationManager(private val context: Context) {
    var notificationsEnabled = true
    val channels = linkedMapOf<String, NotificationChannel>()
    val records = mutableListOf<StatusBarNotification>()
    val created = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val posts = mutableListOf<Pair<String, Int>>()
    val canceled = mutableListOf<Pair<String, Int>>()
    var onNotify: ((StatusBarNotification) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var createFailure: RuntimeException? = null
    var notifyFailure: RuntimeException? = null
    var activeFailure: RuntimeException? = null
    var cancelFailure: RuntimeException? = null
    var deleteFailure: RuntimeException? = null
    var blockCreatedChannel = false
    var registrationCountAtCancel: Int? = null
    private var sequence = 0L
    var activeReads = 0

    fun areNotificationsEnabled() = notificationsEnabled
    fun getNotificationChannel(id: String) = channels[id]
    fun createNotificationChannel(channel: NotificationChannel) {
        created.add(channel.id)
        if (blockCreatedChannel) channel.importance = IMPORTANCE_NONE
        channels[channel.id] = channel
        createFailure?.let { throw it }
    }
    fun notify(tag: String, id: Int, notification: Notification) {
        check(notification.channelId in channels)
        posts.add(tag to id)
        val record = StatusBarNotification(context.packageName, id, tag, "PRIVATE_RAW_KEY_${++sequence}", 1000L + sequence)
        records.add(record)
        onNotify?.invoke(record)
        notifyFailure?.let { throw it }
    }
    val activeNotifications: Array<StatusBarNotification> get() {
        activeReads++
        activeFailure?.let { throw it }
        return records.toTypedArray()
    }
    fun cancel(tag: String, id: Int) {
        canceled.add(tag to id)
        registrationCountAtCancel = context.runtime.eventRegistrations + context.runtime.statusRegistrations + context.runtime.pipelineRegistrations
        cancelFailure?.let { throw it }
        records.removeAll { it.id == id && it.tag == tag }
        onCancel?.invoke()
    }
    fun deleteNotificationChannel(id: String) {
        deleted.add(id)
        deleteFailure?.let { throw it }
        channels.remove(id)
    }
    companion object { const val IMPORTANCE_DEFAULT = 3; const val IMPORTANCE_NONE = 0 }
}
