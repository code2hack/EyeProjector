package android.content

import android.content.pm.PackageManager
import android.os.IBinder
import com.code2hack.eyeprojector.directhost.activation.snapshot.ProbeBoundary

class ComponentName(val context: Context, val type: Class<*>)
class Intent(val context: Context, val type: Class<*>)
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder)
    fun onServiceDisconnected(name: ComponentName)
}
open class Context {
    val packageName = "com.code2hack.eyeprojector.directhost"
    val packageManager = PackageManager()
    fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        ProbeBoundary.androidBinds++
        ProbeBoundary.connect(this, connection)
        return true
    }
    fun unbindService(connection: ServiceConnection) {
        ProbeBoundary.androidUnbinds++
        ProbeBoundary.androidUnbindFailure?.let { throw it }
    }
    companion object { const val BIND_AUTO_CREATE = 1 }
}
