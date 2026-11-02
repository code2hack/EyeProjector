package android.os
class Looper {companion object{fun getMainLooper()=Looper()}}
class Handler(looper:Looper) {
    fun postAtTime(task:Runnable,at:Long){pending[task]=at}
    fun removeCallbacks(task:Runnable){pending.remove(task);onRemove?.invoke(task)}
    fun post(action:()->Unit){action()}
    companion object {val pending=linkedMapOf<Runnable,Long>();var onRemove:((Runnable)->Unit)?=null}
}
object SystemClock {
    fun uptimeMillis()=com.code2hack.eyeprojector.directrg.SystemClock.uptimeMillis()
    fun elapsedRealtimeNanos()=uptimeMillis()*1000000
}
