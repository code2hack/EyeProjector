package android.os

interface IBinder {
    fun interface DeathRecipient { fun binderDied() }
    val isBinderAlive: Boolean
    fun linkToDeath(recipient: DeathRecipient, flags: Int)
    fun unlinkToDeath(recipient: DeathRecipient, flags: Int): Boolean
}
open class Binder : IBinder {
    private val deaths = mutableListOf<IBinder.DeathRecipient>()
    override var isBinderAlive = true
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { deaths.add(recipient) }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = deaths.remove(recipient)
    fun die() { isBinderAlive = false; deaths.toList().forEach { it.binderDied() }; deaths.clear() }
}
