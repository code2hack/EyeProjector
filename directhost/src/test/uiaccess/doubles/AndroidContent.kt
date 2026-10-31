package android.content
open class Context {
    fun <T> getSystemService(type: Class<T>): T = type.getDeclaredConstructor().newInstance()
}
class Intent
