package com.code2hack.eyeprojector.directrg

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask

internal class PresentationSurfaceLost(val surface: Surface?, val generation: Long) : Exception("Presentation Surface unavailable")

/** Real decoder consumer. The last decoded texture survives the absence of a window. */
internal interface ReceiverVideoOutput : Closeable {
    val surface: Surface
    fun attach(next: Surface?, generation: Long)
    fun invalidate()
    fun redraw()
}

internal class RetainedVideoOutput(
    private val presented: (Surface, Long) -> Unit,
    private val failed: (Exception) -> Unit,
) : ReceiverVideoOutput {
    private val thread = HandlerThread("eyeprojector-video-output").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private lateinit var texture: SurfaceTexture
    private var textureName = 0
    private var program = 0
    private var target: Surface? = null
    private var targetGeneration = 0L
    private var hasFrame = false
    @Volatile private var closed = false
    override lateinit var surface: Surface
        private set
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        try { call {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE),
                0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config unavailable" }
            config = configs[0]!!
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context unavailable" }
            buffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(buffer != EGL14.EGL_NO_SURFACE) { "EGL pbuffer unavailable" }
            current(buffer)
            val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureName = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = program()
            texture = SurfaceTexture(textureName)
            surface = Surface(texture)
            texture.setOnFrameAvailableListener({ if (!closed) runFrame() }, handler)
        } } catch (error: Exception) { runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed); throw error }
    }

    private fun <T> call(action: () -> T): T {
        if (Thread.currentThread() === thread) return action()
        val task = FutureTask(action)
        check(handler.post(task)) { "Video output thread stopped" }
        return try { task.get() } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause as? Exception ?: error)
        }
    }

    private fun current(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL makeCurrent failed" }
    }

    /** Waits for pending GL work: SurfaceHolder may release the native window after this returns. */
    override fun attach(next: Surface?, generation: Long) = call {
        if (closed) return@call
        targetGeneration = generation
        if (target === next) { draw(); return@call }
        current(buffer)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE; target = null
        if (next != null) {
            window = EGL14.eglCreateWindowSurface(display, config, next, intArrayOf(EGL14.EGL_NONE), 0)
            if (window == EGL14.EGL_NO_SURFACE) {
                if (windowLost(next)) return@call
                error("EGL window unavailable")
            }
            target = next
            draw()
        }
    }

    /** Geometry replacement must not announce the predecessor's texture as a fresh presentation. */
    override fun invalidate() = call { hasFrame = false }
    override fun redraw() { handler.post { if (!closed) try { draw() } catch (error: Exception) { failed(error) } } }

    private fun runFrame() {
        try {
            current(buffer)
            texture.updateTexImage(); texture.getTransformMatrix(matrix); hasFrame = true
            draw()
        } catch (error: Exception) { if (!closed) failed(error) }
    }

    private fun draw() {
        val owner = target ?: return
        if (!hasFrame) return
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            if (windowLost()) return
            error("Video window makeCurrent failed")
        }
        val size = IntArray(2)
        if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0) ||
            !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)) {
            if (windowLost()) return
            error("Video window geometry unavailable")
        }
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Video GL draw failed" }
        if (!EGL14.eglSwapBuffers(display, window)) {
            if (windowLost()) return
            error("Video window presentation failed")
        }
        presented(owner, targetGeneration) // Submitted to this actual window; no optical-display claim.
    }

    private fun windowLost(failedTarget: Surface? = target): Boolean {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_BAD_SURFACE && error != EGL14.EGL_BAD_NATIVE_WINDOW) return false
        current(buffer)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        window = EGL14.EGL_NO_SURFACE; target = null
        failed(PresentationSurfaceLost(failedTarget, targetGeneration))
        return true
    }

    private fun program(): Int {
        fun shader(type: Int, source: String): Int {
            val value = GLES20.glCreateShader(type)
            try {
                GLES20.glShaderSource(value, source); GLES20.glCompileShader(value)
                val status = IntArray(1); GLES20.glGetShaderiv(value, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetShaderInfoLog(value) }
                return value
            } catch (error: Exception) { GLES20.glDeleteShader(value); throw error }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 position; attribute vec4 coordinate; uniform mat4 transform; varying vec2 uv; void main(){gl_Position=position;uv=(transform*coordinate).xy;}")
        val fragment = try { shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}")
        } catch (error: Exception) { GLES20.glDeleteShader(vertex); throw error }
        val value = GLES20.glCreateProgram()
        try {
            GLES20.glAttachShader(value, vertex); GLES20.glAttachShader(value, fragment); GLES20.glLinkProgram(value)
            val status = IntArray(1); GLES20.glGetProgramiv(value, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(value) }
            return value
        } catch (error: Exception) { GLES20.glDeleteProgram(value); throw error }
        finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
    }

    override fun close() {
        if (closed) return
        try { call {
            if (closed) return@call
            closed = true
            if (::surface.isInitialized) runCatching { surface.release() }
            if (::texture.isInitialized) runCatching { texture.release() }
            if (context != EGL14.EGL_NO_CONTEXT && buffer != EGL14.EGL_NO_SURFACE) runCatching {
                current(buffer)
                GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
            }
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (window != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, window) }
            if (buffer != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, buffer) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }; runCatching { EGL14.eglReleaseThread() }
        } } finally {
            thread.quitSafely()
            if (Thread.currentThread() !== thread) thread.join()
        }
    }
}
