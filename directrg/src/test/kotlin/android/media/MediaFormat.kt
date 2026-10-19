package android.media

class MediaFormat {
    companion object {
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val KEY_MAX_INPUT_SIZE = "max-input-size"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        @JvmStatic fun createVideoFormat(mime: String, width: Int, height: Int) = MediaFormat()
    }
    fun setInteger(key: String, value: Int) = Unit
    fun getInteger(key: String) = 0
    fun containsKey(key: String) = false
}
