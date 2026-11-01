package android.net
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
class LocalSocket:AutoCloseable {
    companion object {
        @Volatile var onConnect: ()->Unit = {}
        @Volatile var onWrite: (ByteArray)->Unit = {}
        @Volatile var onClose: ()->Unit = {}
    }
    val inputStream=ByteArrayInputStream(byteArrayOf())
    val outputStream=object:ByteArrayOutputStream(){
        override fun write(bytes:ByteArray, offset:Int, count:Int){onWrite(bytes.copyOfRange(offset,offset+count));super.write(bytes,offset,count)}
    }
    fun connect(address:LocalSocketAddress){onConnect()}
    override fun close(){onClose()}
}
class LocalServerSocket(name:String){fun accept():LocalSocket=error("No socket/listener in cache-only checks");fun close()=Unit}
class LocalSocketAddress(name:String,namespace:Namespace){enum class Namespace{ABSTRACT}}
