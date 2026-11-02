package android.net
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
class LocalSocket:Closeable {
    private val pipe=PipedInputStream()
    private val writer=PipedOutputStream(pipe)
    val inputStream:InputStream get()=pipe
    val outputStream:OutputStream=ByteArrayOutputStream()
    fun connect(address:LocalSocketAddress)=Unit
    fun send(line:String){writer.write((line+"\n").toByteArray());writer.flush()}
    override fun close(){runCatching{writer.close()};runCatching{pipe.close()}}
}
class LocalSocketAddress(name:String,namespace:Namespace){enum class Namespace{ABSTRACT}}
class LocalServerSocket(name:String):Closeable {
    val queue=LinkedBlockingQueue<LocalSocket>();@Volatile var closed=false
    init{latest=this}
    fun accept():LocalSocket{val next=queue.take();if(closed)throw IOException("closed original listener");return next}
    override fun close(){closed=true;queue.offer(LocalSocket())}
    companion object{@Volatile var latest:LocalServerSocket?=null}
}
