package android.view

import android.graphics.SurfaceTexture

class Surface(val texture: SurfaceTexture? = null) {
    var isValid = true
    var releases = 0
    fun release() { releases++; isValid = false }
}
