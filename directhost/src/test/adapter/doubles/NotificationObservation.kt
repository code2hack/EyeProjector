package com.code2hack.eyeprojector.directhost.notifications
/** Compile-only ingress endpoint. These legacy adapter cases never submit a notification. */
internal class NotificationIngressObservation(val event: Event) {
    class Event(val record: Record)
    class Record(val packageName: String)
}
