package android.util

import java.io.File
import java.io.FileOutputStream

class AtomicFile(private val file: File) {
    companion object { @Volatile var beforeFinish: ((File) -> Unit)? = null }
    fun startWrite() = FileOutputStream(file)
    fun finishWrite(output: FileOutputStream) { beforeFinish?.invoke(file); output.close() }
    fun failWrite(output: FileOutputStream) = output.close()
}
