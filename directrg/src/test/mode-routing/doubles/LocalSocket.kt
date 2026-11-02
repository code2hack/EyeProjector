package android.net
import java.io.*
import java.util.concurrent.*
/** Host acquisition boundary: no Android socket or device connection is opened. */
class LocalSocket:Closeable {
    private val input=PipedInputStream(8192)
    private val peer=PipedOutputStream(input)
    val inputStream:InputStream get()=input
    val outputStream=ByteArrayOutputStream()
    @Volatile var closed=false;private set
    fun send(reports:String) { peer.write(reports.toByteArray(Charsets.US_ASCII));peer.flush() }
    fun connect(address:LocalSocketAddress) {}
    override fun close() { closed=true;peer.close();input.close() }
}
class LocalSocketAddress(name:String,namespace:Namespace) { enum class Namespace { ABSTRACT } }
class LocalServerSocket(val name:String):Closeable {
    private val arrivals=LinkedBlockingQueue<LocalSocket>()
    @Volatile var closed=false;private set
    init { if(servers.putIfAbsent(name,this)!=null) throw IOException("Host listener already exists") }
    fun accept():LocalSocket {
        val next=arrivals.take()
        if(closed) {next.close();throw IOException("Host listener closed")}
        return next
    }
    fun connect():LocalSocket=LocalSocket().also { arrivals.put(it) }
    override fun close() { closed=true;servers.remove(name,this);arrivals.offer(LocalSocket()) }
    companion object { val servers=ConcurrentHashMap<String,LocalServerSocket>() }
}
