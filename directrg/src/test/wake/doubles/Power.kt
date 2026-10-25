package android.os
class PowerManager {
    var isInteractive=false
    val locks=mutableListOf<WakeLock>()
    class WakeLock {var isHeld=false;var acquisitions=0;fun acquire(){isHeld=true;acquisitions++};fun release(){check(isHeld);isHeld=false}}
    fun newWakeLock(flags:Int,tag:String)=WakeLock().also{locks.add(it)}
    companion object{const val SCREEN_BRIGHT_WAKE_LOCK=1;const val ACQUIRE_CAUSES_WAKEUP=2}
}
