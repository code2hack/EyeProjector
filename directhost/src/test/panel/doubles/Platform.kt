package android.os
object Process { fun myUid() = 2000; fun myPid() = 123 }
object Build { const val MODEL = "modeled host adapter" }

object SystemClock { fun uptimeMillis() = System.nanoTime() / 1_000_000 }
