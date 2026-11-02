package android.os
object Build { object VERSION { var SDK_INT=32 } }
object SystemClock { @Volatile var time=1000L;fun uptimeMillis()=time }
class Looper {companion object{fun getMainLooper()=Looper()}}
class Handler(looper:Looper) {
    fun post(action:Runnable):Boolean{postAtTime(action,SystemClock.time);return true}
    fun postAtTime(action:Runnable,at:Long){synchronized(tasks){tasks.add(Task(action,at));(tasks as java.lang.Object).notifyAll()}}
    fun removeCallbacks(action:Runnable){synchronized(tasks){tasks.filter{it.action===action}.forEach{it.canceled=true}}}
    data class Task(val action:Runnable,val at:Long,var canceled:Boolean=false)
    companion object{
        private val tasks=java.util.ArrayList<Task>()
        fun clear(){synchronized(tasks){tasks.clear()}}
        fun awaitPosted(){val until=System.nanoTime()+3_000_000_000L;synchronized(tasks){
            while(tasks.none{!it.canceled&&it.at<=SystemClock.time}){
                check(System.nanoTime()<until){"Actual reader did not post"};(tasks as java.lang.Object).wait(10)
            }
        }}
        fun advance(ms:Long){val end=SystemClock.time+ms
            while(true){val next=synchronized(tasks){tasks.filter{!it.canceled&&it.at<=end}.minByOrNull{it.at}?.also{it.canceled=true}}?:break
                SystemClock.time=maxOf(SystemClock.time,next.at);next.action.run()}
            SystemClock.time=end
        }
    }
}
