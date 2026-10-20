package android.content
import java.io.InputStream
class ApplicationInfo(val uid:Int=424242,val sourceDir:String="not-used-by-host-route-tests")
class Assets { fun open(name:String):InputStream=error("Host route tests must not provision Android assets") }
class Context {
    val packageName="example.host.routing.fixture"
    val applicationInfo=ApplicationInfo()
    val assets=Assets()
}
