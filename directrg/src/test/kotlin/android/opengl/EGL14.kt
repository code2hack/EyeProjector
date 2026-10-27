package android.opengl

import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

class EGLDisplay
class EGLContext
class EGLConfig
class EGLSurface(val native: Surface? = null)
object EGL14 {
    const val EGL_BAD_SURFACE = 12301
    const val EGL_BAD_NATIVE_WINDOW = 12299
    @Volatile var error = 0
    @JvmStatic fun eglGetError() = error.also { error = 0 }
    const val EGL_DEFAULT_DISPLAY = 0
    const val EGL_RENDERABLE_TYPE = 1
    const val EGL_OPENGL_ES2_BIT = 2
    const val EGL_SURFACE_TYPE = 3
    const val EGL_WINDOW_BIT = 4
    const val EGL_PBUFFER_BIT = 8
    const val EGL_RED_SIZE = 9
    const val EGL_GREEN_SIZE = 10
    const val EGL_BLUE_SIZE = 11
    const val EGL_NONE = 12
    const val EGL_CONTEXT_CLIENT_VERSION = 13
    const val EGL_WIDTH = 14
    const val EGL_HEIGHT = 15
    @JvmField val EGL_NO_CONTEXT = EGLContext()
    @JvmField val EGL_NO_SURFACE = EGLSurface()
    val swaps = CopyOnWriteArrayList<Surface>()
    val destroyed = CopyOnWriteArrayList<EGLSurface>()
    val terminated = CopyOnWriteArrayList<EGLDisplay>()
    @Volatile var failCurrent = false
    @Volatile var failSwap = false
    @Volatile var failQuery = false
    @JvmStatic fun eglGetDisplay(id: Int) = EGLDisplay()
    @JvmStatic fun eglInitialize(d: EGLDisplay, major: IntArray, mo: Int, minor: IntArray, no: Int) = true
    @JvmStatic fun eglChooseConfig(d: EGLDisplay, attributes: IntArray, offset: Int, configs: Array<EGLConfig?>, co: Int, count: Int, sizes: IntArray, so: Int): Boolean {
        configs[co] = EGLConfig(); sizes[so] = 1; return true
    }
    @JvmStatic fun eglCreateContext(d: EGLDisplay, c: EGLConfig, old: EGLContext, attributes: IntArray, offset: Int) = EGLContext()
    @JvmStatic fun eglCreatePbufferSurface(d: EGLDisplay, c: EGLConfig, attributes: IntArray, offset: Int) = EGLSurface()
    @JvmStatic fun eglCreateWindowSurface(d: EGLDisplay, c: EGLConfig, surface: Any, attributes: IntArray, offset: Int) = EGLSurface(surface as Surface)
    @JvmStatic fun eglMakeCurrent(d: EGLDisplay, draw: EGLSurface, read: EGLSurface, c: EGLContext) = !failCurrent
    @JvmStatic fun eglDestroySurface(d: EGLDisplay, s: EGLSurface): Boolean { destroyed.add(s); return true }
    @JvmStatic fun eglDestroyContext(d: EGLDisplay, c: EGLContext) = true
    @JvmStatic fun eglTerminate(d: EGLDisplay): Boolean { terminated.add(d); return true }
    @JvmStatic fun eglReleaseThread() = true
    @JvmStatic fun eglQuerySurface(d: EGLDisplay, s: EGLSurface, attribute: Int, value: IntArray, offset: Int): Boolean { value[offset] = 640; return !failQuery }
    @JvmStatic fun eglSwapBuffers(d: EGLDisplay, s: EGLSurface): Boolean {
        check(s.native?.isValid == true) { "Rendering to released window" }
        if (failSwap) return false
        swaps.add(s.native); return true
    }
}
