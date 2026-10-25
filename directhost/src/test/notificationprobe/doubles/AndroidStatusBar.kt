package android.service.notification

/** Payload access deliberately fails; only app-owned notification identity metadata is modeled. */
open class StatusBarNotification(
    open val packageName: String,
    open val id: Int,
    open val tag: String?,
    open val key: String,
    open val postTime: Long,
) {
    val notification: Any get() = error("PRIVATE_PAYLOAD_MUST_NOT_BE_READ")
    override fun toString(): String = error("RAW_PLATFORM_NOTIFICATION_MUST_NOT_BE_RENDERED")
}
