package android.net
class Uri(private val value: String) {
    override fun toString() = value
    class Builder {
        private var scheme = ""
        private var authority = ""
        private val paths = mutableListOf<String>()
        fun scheme(value: String): Builder { scheme = value; return this }
        fun authority(value: String): Builder { authority = value; return this }
        fun appendPath(value: String): Builder { paths.add(java.net.URLEncoder.encode(value, "UTF-8")); return this }
        fun build() = Uri("$scheme://$authority/" + paths.joinToString("/"))
    }
}
