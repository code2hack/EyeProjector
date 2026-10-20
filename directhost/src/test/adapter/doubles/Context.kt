package android.content
import java.io.File
open class Context(val filesDir: File) {
 val packageManager = android.content.pm.PackageManager()
 val packageName = "review.fake"
 val applicationInfo = ApplicationInfo()
 val assets = Assets()
 val applicationContext: Context get() = this
}
class ComponentName(context: Context, klass: Class<*>)
interface ServiceConnection { fun onServiceConnected(name: ComponentName, binder: android.os.IBinder); fun onServiceDisconnected(name: ComponentName) }

class ApplicationInfo { val uid = 10001; val sourceDir = "unused-modeled.apk" }
class Assets { fun open(name: String): java.io.InputStream = error("Capture/bootstrap creation is not run by host composition") }
