package android.media

import java.nio.ByteBuffer

/** Mutable native-boundary facts. No codec capability, CSD validity or encoder behavior is modeled. */
class MediaFormat {
    val values = linkedMapOf<String, Any?>()
    val keys: Set<String> get() = values.keys.toSet()
    fun containsKey(name: String): Boolean = values.containsKey(name)
    fun setInteger(name: String, value: Int) { values[name] = value }
    fun setLong(name: String, value: Long) { values[name] = value }
    fun setFloat(name: String, value: Float) { values[name] = value }
    fun setString(name: String, value: String?) { values[name] = value }
    fun setByteBuffer(name: String, value: ByteBuffer?) { values[name] = value }
    fun getInteger(name: String): Int = values.getValue(name) as Int
    fun getInteger(name: String, defaultValue: Int): Int = values[name] as? Int ?: defaultValue
    fun getLong(name: String): Long = values.getValue(name) as Long
    fun getFloat(name: String): Float = values.getValue(name) as Float
    fun getString(name: String): String? = values[name] as String?
    fun getByteBuffer(name: String): ByteBuffer? = values[name] as ByteBuffer?
    override fun toString(): String = values.toString()

    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val KEY_MIME = "mime"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_COLOR_FORMAT = "color-format"
        const val KEY_BIT_RATE = "bitrate"
        const val KEY_FRAME_RATE = "frame-rate"
        const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
        const val KEY_BITRATE_MODE = "bitrate-mode"
        @JvmStatic fun createVideoFormat(mime: String, width: Int, height: Int) = MediaFormat().apply {
            setString(KEY_MIME, mime); setInteger(KEY_WIDTH, width); setInteger(KEY_HEIGHT, height)
        }
    }
}
