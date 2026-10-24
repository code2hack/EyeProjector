package android.util
import java.io.File
import java.io.FileOutputStream
class AtomicFile(val file: File) {
 fun startWrite():FileOutputStream { beforeStart?.invoke(file);starts.incrementAndGet();return FileOutputStream(file) }
 companion object {var beforeStart:((File)->Unit)?=null;val starts=java.util.concurrent.atomic.AtomicInteger()}
 fun finishWrite(out: FileOutputStream) { out.close() }
 fun failWrite(out: FileOutputStream) { out.close() }
}
