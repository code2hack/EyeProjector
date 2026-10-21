package android.net
import java.io.*
class LocalSocket(
    val outputStream: OutputStream = ByteArrayOutputStream(),
    private val onClose: () -> Unit = {},
) : Closeable {
    val inputStream: InputStream = ByteArrayInputStream(byteArrayOf())
    override fun close() { onClose() }
}
class LocalServerSocket(name: String) : Closeable {
    fun accept(): LocalSocket = error("Not used: adapter does not run Android capture")
    override fun close() = Unit
}
