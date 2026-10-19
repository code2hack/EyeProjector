package android.util

import java.io.File
import java.io.FileOutputStream

class AtomicFile(private val file: File) {
    fun startWrite() = FileOutputStream(file)
    fun finishWrite(output: FileOutputStream) = output.close()
    fun failWrite(output: FileOutputStream) = output.close()
}
