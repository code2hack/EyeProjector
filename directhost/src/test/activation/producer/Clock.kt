package android.os
/** Named uptime boundary for actual HID source; no Android scheduling emulated. */
object SystemClock {fun uptimeMillis()=System.nanoTime()/1_000_000}
