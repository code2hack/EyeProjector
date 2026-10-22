package android.net
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
open class LocalSocket {
    var released = false
    var source: InputStream = ByteArrayInputStream(byteArrayOf())
    var sink: OutputStream = ByteArrayOutputStream()
    val inputStream get() = source
    val outputStream get() = sink
    open fun close() { released = true; source.close(); sink.close() }
}
class LocalServerSocket(name: String) {
    fun accept(): LocalSocket = error("No device sockets in host launch composition")
    fun close() {}
}
