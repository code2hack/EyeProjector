package android.os
object SystemClock { fun uptimeMillis()=1000L;fun elapsedRealtimeNanos()=1_000_000_000L }
object Build { object VERSION { const val SDK_INT=35 } }
class Looper { companion object { private val main=Looper();fun myLooper()=main;fun getMainLooper()=main } }
class Handler(looper:Looper) { fun postAtTime(task:Runnable,deadline:Long)=true;fun removeCallbacks(task:Runnable) {} }
