package android.content

import java.io.File

/** Only the application Context/filesDir boundary is doubled; the coordinate owner is actual. */
open class Context(private val directory: File) {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = directory
}
