package android.os
object Build { const val MODEL = "modeled-host" }
object Process { fun myUid() = 2000; fun myPid() = 42; fun myTid() = 43 }

object SystemClock { fun uptimeMillis() = System.nanoTime() / 1_000_000 }
