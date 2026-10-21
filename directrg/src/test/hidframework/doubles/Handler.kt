package android.os

class Looper private constructor() {
    companion object {
        private val main = Looper()
        fun getMainLooper() = main
    }
}
class Handler(val looper: Looper)
