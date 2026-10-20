package android.content
import java.io.File
open class Context(val filesDir: File) {
 val packageManager = android.content.pm.PackageManager()
 val packageName = "review.fake"
 val applicationContext: Context get() = this
}
class ComponentName(context: Context, klass: Class<*>)
interface ServiceConnection { fun onServiceConnected(name: ComponentName, binder: android.os.IBinder); fun onServiceDisconnected(name: ComponentName) }
