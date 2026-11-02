package android.content

import java.io.File

/** Only Android's application Context/filesDir boundary; all storage/codec/store code is actual. */
open class Context(private val directory: File) {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = directory
}
