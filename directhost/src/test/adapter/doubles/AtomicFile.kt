package android.util
import java.io.File
import java.io.FileOutputStream
class AtomicFile(val file: File) {
 fun startWrite() = FileOutputStream(file)
 fun finishWrite(out: FileOutputStream) { out.close() }
 fun failWrite(out: FileOutputStream) { out.close() }
}
