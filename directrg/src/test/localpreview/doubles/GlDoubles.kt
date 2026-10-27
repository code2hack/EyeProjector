package android.opengl

import android.graphics.SurfaceTextureProbe
import android.os.HandlerThreadProbe
import android.view.Surface
import android.view.SurfaceProbe
import java.nio.Buffer
import java.nio.FloatBuffer

class EGLDisplay internal constructor(val id: Int)
class EGLConfig internal constructor(val id: Int)
class EGLContext internal constructor(val id: Int)
class EGLSurface internal constructor(
    val id: Int,
    val kind: String,
    val nativeWindow: Surface? = null,
    val requestedWidth: Int = 0,
    val requestedHeight: Int = 0,
) {
    val width: Int get() = nativeWindow?.width ?: requestedWidth
    val height: Int get() = nativeWindow?.height ?: requestedHeight
}

data class GlEvent(val name: String, val thread: String, val threadId: Long, val details: Map<String, Any?>)
data class GlRect(val x: Int, val y: Int, val width: Int, val height: Int)
data class TextureBinding(val unit: Int, val target: Int)
data class VertexSnapshot(
    val size: Int,
    val type: Int,
    val normalized: Boolean,
    val stride: Int,
    val bufferPosition: Int,
    val values: List<Float>,
    val allValues: List<Float>,
    val enabled: Boolean,
)
data class DrawSnapshot(
    val program: Int,
    val programSources: List<String>,
    val texture: Int,
    val activeTexture: Int,
    val boundTextures: Map<TextureBinding, Int>,
    val viewport: GlRect,
    val scissor: GlRect,
    val scissorEnabled: Boolean,
    val enabledCaps: Set<Int>,
    val uniformMatrices: Map<String, List<Float>>,
    val uniformInts: Map<String, Int>,
    val uniformFloats: Map<String, List<Float>>,
    val vertices: Map<Int, VertexSnapshot>,
    val attributes: Map<String, VertexSnapshot>,
    val mode: Int,
    val first: Int,
    val count: Int,
    val eglSurface: EGLSurface,
    val success: Boolean,
    val error: Int,
    val thread: String,
    val threadId: Long,
) {
    val transform: List<Float> get() = uniformMatrices["transform"].orEmpty()
}
data class SwapSnapshot(
    val surface: EGLSurface,
    val width: Int,
    val height: Int,
    val draws: List<DrawSnapshot>,
    val success: Boolean,
    val error: Int,
    val thread: String,
    val threadId: Long,
)
data class SurfaceQuery(val surface: EGLSurface, val attribute: Int, val value: Int, val success: Boolean)

/** Flags are consumed once, by the named API. Errors can be changed before setting the flag. */
class GlFailures {
    @Volatile var initialize = false
    @Volatile var chooseConfig = false
    @Volatile var createContext = false
    @Volatile var createPbuffer = false
    @Volatile var createWindow = false
    @Volatile var makeCurrent = false
    @Volatile var windowMakeCurrent = false
    @Volatile var querySurface = false
    @Volatile var createProgram = false
    @Volatile var linkProgram = false
    @Volatile var compileShader = false
    @Volatile var surfaceTexture = false
    @Volatile var surface = false
    @Volatile var updateTexImage = false
    @Volatile var getTransformMatrix = false
    @Volatile var draw = false
    /** Absolute glDrawArrays ordinal since reset, independent of any renderer/pass identity. */
    @Volatile var drawAtOrdinal = 0
    @Volatile var windowSwap = false
    @Volatile var deleteProgram = false
    @Volatile var deleteTextures = false
    @Volatile var drawError = GLES20.GL_INVALID_OPERATION
    @Volatile var windowError = EGL14.EGL_BAD_SURFACE
    @Volatile var makeCurrentError = EGL14.EGL_CONTEXT_LOST
    @Volatile var contextError = EGL14.EGL_BAD_ALLOC

    @Synchronized internal fun take(name: String): Boolean = when (name) {
        "initialize" -> initialize.also { initialize = false }
        "chooseConfig" -> chooseConfig.also { chooseConfig = false }
        "createContext" -> createContext.also { createContext = false }
        "createPbuffer" -> createPbuffer.also { createPbuffer = false }
        "createWindow" -> createWindow.also { createWindow = false }
        "makeCurrent" -> makeCurrent.also { makeCurrent = false }
        "windowMakeCurrent" -> windowMakeCurrent.also { windowMakeCurrent = false }
        "querySurface" -> querySurface.also { querySurface = false }
        "createProgram" -> createProgram.also { createProgram = false }
        "linkProgram" -> linkProgram.also { linkProgram = false }
        "compileShader" -> compileShader.also { compileShader = false }
        "surfaceTexture" -> surfaceTexture.also { surfaceTexture = false }
        "surface" -> surface.also { surface = false }
        "updateTexImage" -> updateTexImage.also { updateTexImage = false }
        "getTransformMatrix" -> getTransformMatrix.also { getTransformMatrix = false }
        "draw" -> draw.also { draw = false }
        "windowSwap" -> windowSwap.also { windowSwap = false }
        "deleteProgram" -> deleteProgram.also { deleteProgram = false }
        "deleteTextures" -> deleteTextures.also { deleteTextures = false }
        else -> error("Unknown injected operation: $name")
    }

    @Synchronized internal fun takeDrawOrdinal(ordinal: Int): Boolean {
        if (drawAtOrdinal != ordinal) return false
        drawAtOrdinal = 0
        return true
    }
}

internal data class AttributePointer(
    val size: Int, val type: Int, val normalized: Boolean, val stride: Int,
    val buffer: FloatBuffer, val position: Int, val limit: Int,
) {
    fun snapshot(enabled: Boolean): VertexSnapshot {
        val copy = buffer.duplicate()
        copy.clear()
        val all = FloatArray(copy.remaining())
        copy.get(all)
        return VertexSnapshot(size, type, normalized, stride, position,
            all.slice(position until limit), all.toList(), enabled)
    }
}
internal class GlState {
    var program = 0
    var activeTexture = GLES20.GL_TEXTURE0
    val textures = mutableMapOf<TextureBinding, Int>()
    var viewport = GlRect(0, 0, 0, 0)
    var scissor = GlRect(0, 0, 0, 0)
    val enabledCaps = mutableSetOf<Int>()
    val enabledAttributes = mutableSetOf<Int>()
    val pointers = mutableMapOf<Int, AttributePointer>()
    val matrices = mutableMapOf<Pair<Int, Int>, List<Float>>()
    val uniformInts = mutableMapOf<Pair<Int, Int>, Int>()
    val uniformFloats = mutableMapOf<Pair<Int, Int>, List<Float>>()
    var glError = GLES20.GL_NO_ERROR
    var eglError = EGL14.EGL_SUCCESS
    var currentSurface = EGL14.EGL_NO_SURFACE
    val pendingDraws = mutableListOf<DrawSnapshot>()
}

/** An API-call recorder, not an EGL/GPU implementation or a claim of physical presentation. */
object GlProbe {
    private val lock = Any()
    @Volatile var failures = GlFailures()
    private val recordedEvents = mutableListOf<GlEvent>()
    private val recordedDraws = mutableListOf<DrawSnapshot>()
    private val recordedSwaps = mutableListOf<SwapSnapshot>()
    private val recordedQueries = mutableListOf<SurfaceQuery>()
    private val states = mutableMapOf<Long, GlState>()
    private var shaderSequence = 1
    private var programSequence = 100
    private var textureSequence = 1000
    private var eglSequence = 1
    internal val shaderSources = mutableMapOf<Int, String>()
    internal val shaderCompiled = mutableMapOf<Int, Boolean>()
    internal val programShaders = mutableMapOf<Int, MutableList<Int>>()
    internal val programLinked = mutableMapOf<Int, Boolean>()
    internal val attributeNames = mutableMapOf<Int, MutableMap<String, Int>>()
    internal val uniformNames = mutableMapOf<Int, MutableMap<String, Int>>()
    private val madePrograms = mutableListOf<Int>()
    private val madeTextures = mutableListOf<Int>()
    private val madeContexts = mutableListOf<EGLContext>()
    private val madeSurfaces = mutableListOf<EGLSurface>()
    private val removedShaders = mutableListOf<Int>()
    private val removedPrograms = mutableListOf<Int>()
    private val removedTextures = mutableListOf<Int>()
    private val removedContexts = mutableListOf<EGLContext>()
    private val removedSurfaces = mutableListOf<EGLSurface>()

    val events: List<GlEvent> get() = synchronized(lock) { recordedEvents.toList() }
    val timeline: List<GlEvent> get() = events
    val draws: List<DrawSnapshot> get() = synchronized(lock) { recordedDraws.toList() }
    val swaps: List<SwapSnapshot> get() = synchronized(lock) { recordedSwaps.toList() }
    val queries: List<SurfaceQuery> get() = synchronized(lock) { recordedQueries.toList() }
    val createdPrograms: List<Int> get() = synchronized(lock) { madePrograms.toList() }
    val createdTextures: List<Int> get() = synchronized(lock) { madeTextures.toList() }
    val createdContexts: List<EGLContext> get() = synchronized(lock) { madeContexts.toList() }
    val createdSurfaces: List<EGLSurface> get() = synchronized(lock) { madeSurfaces.toList() }
    val deletedShaders: List<Int> get() = synchronized(lock) { removedShaders.toList() }
    val deletedPrograms: List<Int> get() = synchronized(lock) { removedPrograms.toList() }
    val deletedTextures: List<Int> get() = synchronized(lock) { removedTextures.toList() }
    val destroyedContexts: List<EGLContext> get() = synchronized(lock) { removedContexts.toList() }
    val destroyedSurfaces: List<EGLSurface> get() = synchronized(lock) { removedSurfaces.toList() }

    fun record(name: String, vararg details: Pair<String, Any?>) = synchronized(lock) {
        val thread = Thread.currentThread()
        recordedEvents.add(GlEvent(name, thread.name, thread.id, details.toMap()))
        Unit
    }
    fun takeFailure(name: String): Boolean = failures.take(name)
    internal fun state(): GlState = synchronized(lock) { states.getOrPut(Thread.currentThread().id) { GlState() } }
    /** Read existing owner state at a callback without creating a fake caller-thread GL state. */
    internal fun observedState(thread: Thread): GlState? = synchronized(lock) { states[thread.id] }
    internal fun shaderId(): Int = synchronized(lock) { shaderSequence++ }
    internal fun programId(): Int = synchronized(lock) {
        val id = programSequence++
        madePrograms.add(id)
        id
    }
    internal fun textureId(): Int = synchronized(lock) {
        val id = textureSequence++
        madeTextures.add(id)
        id
    }
    internal fun eglId(): Int = synchronized(lock) { eglSequence++ }
    internal fun contextCreated(value: EGLContext) = synchronized(lock) { madeContexts.add(value); Unit }
    internal fun surfaceCreated(value: EGLSurface) = synchronized(lock) { madeSurfaces.add(value); Unit }
    internal fun shaderDeleted(value: Int) = synchronized(lock) { if (value != 0) removedShaders.add(value); Unit }
    internal fun programDeleted(value: Int) = synchronized(lock) { if (value != 0) removedPrograms.add(value); Unit }
    internal fun textureDeleted(value: Int) = synchronized(lock) { if (value != 0) removedTextures.add(value); Unit }
    internal fun contextDestroyed(value: EGLContext) = synchronized(lock) { removedContexts.add(value); Unit }
    internal fun surfaceDestroyed(value: EGLSurface) = synchronized(lock) { removedSurfaces.add(value); Unit }
    internal fun query(value: SurfaceQuery) = synchronized(lock) { recordedQueries.add(value); Unit }

    internal fun draw(mode: Int, first: Int, count: Int) {
        val state = state()
        val ordinal = synchronized(lock) { recordedDraws.size + 1 }
        val injected = takeFailure("draw") || failures.takeDrawOrdinal(ordinal)
        val error = if (injected) failures.drawError else GLES20.GL_NO_ERROR
        state.glError = error
        val pointers = state.pointers.mapValues { (location, pointer) -> pointer.snapshot(location in state.enabledAttributes) }
        val thread = Thread.currentThread()
        val matrices = uniformNames[state.program].orEmpty().mapNotNull { (name, location) ->
            state.matrices[state.program to location]?.let { name to it.toList() }
        }.toMap()
        val ints = uniformNames[state.program].orEmpty().mapNotNull { (name, location) ->
            state.uniformInts[state.program to location]?.let { name to it }
        }.toMap()
        val floats = uniformNames[state.program].orEmpty().mapNotNull { (name, location) ->
            state.uniformFloats[state.program to location]?.let { name to it.toList() }
        }.toMap()
        val attributes = attributeNames[state.program].orEmpty().mapNotNull { (name, location) ->
            pointers[location]?.let { name to it }
        }.toMap()
        val draw = DrawSnapshot(state.program,
            programShaders[state.program].orEmpty().mapNotNull { shaderSources[it] },
            state.textures[TextureBinding(state.activeTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)] ?: 0,
            state.activeTexture, state.textures.toMap(), state.viewport, state.scissor,
            GLES20.GL_SCISSOR_TEST in state.enabledCaps, state.enabledCaps.toSet(),
            matrices, ints, floats, pointers, attributes, mode, first, count,
            state.currentSurface, error == GLES20.GL_NO_ERROR, error, thread.name, thread.id)
        synchronized(lock) { recordedDraws.add(draw) }
        state.pendingDraws.add(draw)
        record("glDrawArrays", "snapshot" to draw)
    }

    internal fun swap(surface: EGLSurface, success: Boolean, error: Int) {
        val state = state()
        val thread = Thread.currentThread()
        val swap = SwapSnapshot(surface, surface.width, surface.height, state.pendingDraws.toList(),
            success, error, thread.name, thread.id)
        state.pendingDraws.clear()
        synchronized(lock) { recordedSwaps.add(swap) }
        record("eglSwapBuffers", "snapshot" to swap)
    }

    fun reset() {
        synchronized(lock) {
            failures = GlFailures()
            recordedEvents.clear(); recordedDraws.clear(); recordedSwaps.clear(); recordedQueries.clear(); states.clear()
            shaderSequence = 1; programSequence = 100; textureSequence = 1000; eglSequence = 1
            shaderSources.clear(); shaderCompiled.clear(); programShaders.clear(); programLinked.clear()
            attributeNames.clear(); uniformNames.clear()
            madePrograms.clear(); madeTextures.clear(); madeContexts.clear(); madeSurfaces.clear()
            removedShaders.clear(); removedPrograms.clear(); removedTextures.clear(); removedContexts.clear(); removedSurfaces.clear()
        }
        SurfaceTextureProbe.reset()
        SurfaceProbe.reset()
        HandlerThreadProbe.reset()
    }
}

object EGL14 {
    const val EGL_DEFAULT_DISPLAY = 0
    const val EGL_SUCCESS = 0x3000
    const val EGL_BAD_ALLOC = 0x3003
    const val EGL_BAD_NATIVE_WINDOW = 0x300B
    const val EGL_BAD_SURFACE = 0x300D
    const val EGL_CONTEXT_LOST = 0x300E
    const val EGL_NONE = 0x3038
    const val EGL_RED_SIZE = 0x3024
    const val EGL_GREEN_SIZE = 0x3023
    const val EGL_BLUE_SIZE = 0x3022
    const val EGL_RENDERABLE_TYPE = 0x3040
    const val EGL_OPENGL_ES2_BIT = 4
    const val EGL_SURFACE_TYPE = 0x3033
    const val EGL_WINDOW_BIT = 4
    const val EGL_PBUFFER_BIT = 1
    const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    const val EGL_WIDTH = 0x3057
    const val EGL_HEIGHT = 0x3056
    val EGL_NO_CONTEXT = EGLContext(0)
    val EGL_NO_SURFACE = EGLSurface(0, "none")
    val EGL_NO_DISPLAY = EGLDisplay(0)

    fun eglGetDisplay(displayId: Int): EGLDisplay {
        GlProbe.record("eglGetDisplay", "displayId" to displayId)
        return EGLDisplay(1)
    }
    fun eglInitialize(display: EGLDisplay, major: IntArray, majorOffset: Int, minor: IntArray, minorOffset: Int): Boolean {
        GlProbe.record("eglInitialize", "display" to display.id)
        if (GlProbe.takeFailure("initialize")) { GlProbe.state().eglError = EGL_BAD_ALLOC; return false }
        major[majorOffset] = 1; minor[minorOffset] = 4
        return true
    }
    fun eglChooseConfig(display: EGLDisplay, attributes: IntArray, attributesOffset: Int,
        configs: Array<EGLConfig?>, configsOffset: Int, configSize: Int, count: IntArray, countOffset: Int): Boolean {
        GlProbe.record("eglChooseConfig", "display" to display.id, "attributes" to attributes.drop(attributesOffset))
        if (GlProbe.takeFailure("chooseConfig")) { count[countOffset] = 0; return false }
        count[countOffset] = if (configSize > 0) 1 else 0
        if (configSize > 0) configs[configsOffset] = EGLConfig(1)
        return true
    }
    fun eglCreateContext(display: EGLDisplay, config: EGLConfig, shareContext: EGLContext,
        attributes: IntArray, offset: Int): EGLContext {
        GlProbe.record("eglCreateContext", "display" to display.id, "config" to config.id,
            "share" to shareContext.id, "attributes" to attributes.drop(offset))
        if (GlProbe.takeFailure("createContext")) { GlProbe.state().eglError = GlProbe.failures.contextError; return EGL_NO_CONTEXT }
        return EGLContext(GlProbe.eglId()).also { GlProbe.contextCreated(it) }
    }
    private fun attribute(attributes: IntArray, offset: Int, name: Int): Int {
        var index = offset
        while (index + 1 < attributes.size && attributes[index] != EGL_NONE) {
            if (attributes[index] == name) return attributes[index + 1]
            index += 2
        }
        return 0
    }
    fun eglCreatePbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray, offset: Int): EGLSurface {
        val width = attribute(attributes, offset, EGL_WIDTH)
        val height = attribute(attributes, offset, EGL_HEIGHT)
        GlProbe.record("eglCreatePbufferSurface", "display" to display.id, "config" to config.id, "width" to width, "height" to height)
        if (GlProbe.takeFailure("createPbuffer")) { GlProbe.state().eglError = EGL_BAD_ALLOC; return EGL_NO_SURFACE }
        return EGLSurface(GlProbe.eglId(), "pbuffer", requestedWidth = width, requestedHeight = height)
            .also { GlProbe.surfaceCreated(it) }
    }
    fun eglCreateWindowSurface(display: EGLDisplay, config: EGLConfig, nativeWindow: Any, attributes: IntArray, offset: Int): EGLSurface {
        GlProbe.record("eglCreateWindowSurface", "display" to display.id, "config" to config.id,
            "window" to nativeWindow, "attributes" to attributes.drop(offset))
        if (GlProbe.takeFailure("createWindow")) { GlProbe.state().eglError = GlProbe.failures.windowError; return EGL_NO_SURFACE }
        val window = nativeWindow as Surface
        return EGLSurface(GlProbe.eglId(), "window", window, window.width, window.height).also { GlProbe.surfaceCreated(it) }
    }
    fun eglMakeCurrent(display: EGLDisplay, draw: EGLSurface, read: EGLSurface, context: EGLContext): Boolean {
        GlProbe.record("eglMakeCurrent", "display" to display.id, "draw" to draw.id, "read" to read.id, "context" to context.id)
        if (GlProbe.takeFailure("makeCurrent") || (draw.kind == "window" && GlProbe.takeFailure("windowMakeCurrent"))) {
            GlProbe.state().eglError = GlProbe.failures.makeCurrentError
            return false
        }
        GlProbe.state().currentSurface = draw
        return true
    }
    fun eglQuerySurface(display: EGLDisplay, surface: EGLSurface, attribute: Int, result: IntArray, offset: Int): Boolean {
        val success = !GlProbe.takeFailure("querySurface")
        val value = if (attribute == EGL_WIDTH) surface.width else if (attribute == EGL_HEIGHT) surface.height else 0
        if (success) result[offset] = value else GlProbe.state().eglError = GlProbe.failures.windowError
        GlProbe.query(SurfaceQuery(surface, attribute, value, success))
        GlProbe.record("eglQuerySurface", "display" to display.id, "surface" to surface.id, "attribute" to attribute,
            "value" to value, "success" to success)
        return success
    }
    fun eglSwapBuffers(display: EGLDisplay, surface: EGLSurface): Boolean {
        val error = if (GlProbe.takeFailure("windowSwap")) GlProbe.failures.windowError else EGL_SUCCESS
        if (error != EGL_SUCCESS) GlProbe.state().eglError = error
        GlProbe.swap(surface, error == EGL_SUCCESS, error)
        return error == EGL_SUCCESS
    }
    fun eglGetError(): Int {
        val state = GlProbe.state()
        val error = state.eglError
        state.eglError = EGL_SUCCESS
        GlProbe.record("eglGetError", "error" to error)
        return error
    }
    fun eglDestroySurface(display: EGLDisplay, surface: EGLSurface): Boolean {
        GlProbe.record("eglDestroySurface", "display" to display.id, "surface" to surface.id)
        GlProbe.surfaceDestroyed(surface)
        return true
    }
    fun eglDestroyContext(display: EGLDisplay, context: EGLContext): Boolean {
        GlProbe.record("eglDestroyContext", "display" to display.id, "context" to context.id)
        GlProbe.contextDestroyed(context)
        return true
    }
    fun eglTerminate(display: EGLDisplay): Boolean { GlProbe.record("eglTerminate", "display" to display.id); return true }
    fun eglReleaseThread(): Boolean { GlProbe.record("eglReleaseThread"); return true }
}

object GLES11Ext {
    const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
}

object GLES20 {
    const val GL_NO_ERROR = 0
    const val GL_INVALID_OPERATION = 0x0502
    const val GL_CONTEXT_LOST = 0x0507
    const val GL_TEXTURE0 = 0x84C0
    const val GL_TEXTURE_2D = 0x0DE1
    const val GL_TEXTURE_MIN_FILTER = 0x2801
    const val GL_TEXTURE_MAG_FILTER = 0x2800
    const val GL_TEXTURE_WRAP_S = 0x2802
    const val GL_TEXTURE_WRAP_T = 0x2803
    const val GL_LINEAR = 0x2601
    const val GL_CLAMP_TO_EDGE = 0x812F
    const val GL_FLOAT = 0x1406
    const val GL_TRIANGLE_STRIP = 5
    const val GL_TRIANGLES = 4
    const val GL_VERTEX_SHADER = 0x8B31
    const val GL_FRAGMENT_SHADER = 0x8B30
    const val GL_COMPILE_STATUS = 0x8B81
    const val GL_LINK_STATUS = 0x8B82
    const val GL_SCISSOR_TEST = 0x0C11
    const val GL_BLEND = 0x0BE2
    const val GL_COLOR_BUFFER_BIT = 0x4000

    fun glGenTextures(count: Int, names: IntArray, offset: Int) {
        repeat(count) { names[offset + it] = GlProbe.textureId() }
        GlProbe.record("glGenTextures", "textures" to names.slice(offset until offset + count))
    }
    fun glBindTexture(target: Int, texture: Int) {
        val state = GlProbe.state(); state.textures[TextureBinding(state.activeTexture, target)] = texture
        GlProbe.record("glBindTexture", "target" to target, "texture" to texture)
    }
    fun glTexParameteri(target: Int, parameter: Int, value: Int) {
        GlProbe.record("glTexParameteri", "target" to target, "parameter" to parameter, "value" to value)
    }
    fun glViewport(x: Int, y: Int, width: Int, height: Int) {
        val rect = GlRect(x, y, width, height); GlProbe.state().viewport = rect
        GlProbe.record("glViewport", "rect" to rect)
    }
    fun glScissor(x: Int, y: Int, width: Int, height: Int) {
        val rect = GlRect(x, y, width, height); GlProbe.state().scissor = rect
        GlProbe.record("glScissor", "rect" to rect)
    }
    fun glEnable(cap: Int) { GlProbe.state().enabledCaps.add(cap); GlProbe.record("glEnable", "cap" to cap) }
    fun glDisable(cap: Int) { GlProbe.state().enabledCaps.remove(cap); GlProbe.record("glDisable", "cap" to cap) }
    fun glUseProgram(program: Int) { GlProbe.state().program = program; GlProbe.record("glUseProgram", "program" to program) }
    fun glActiveTexture(texture: Int) { GlProbe.state().activeTexture = texture; GlProbe.record("glActiveTexture", "texture" to texture) }
    fun glGetUniformLocation(program: Int, name: String): Int {
        val names = GlProbe.uniformNames.getOrPut(program) { mutableMapOf() }
        val location = names.getOrPut(name) { when (name) { "image" -> 0; "transform" -> 1; else -> 16 + names.size } }
        GlProbe.record("glGetUniformLocation", "program" to program, "name" to name, "location" to location)
        return location
    }
    fun glUniform1i(location: Int, value: Int) {
        val state = GlProbe.state(); state.uniformInts[state.program to location] = value
        GlProbe.record("glUniform1i", "location" to location, "value" to value)
    }
    fun glUniform1f(location: Int, value: Float) = uniformFloats(location, listOf(value))
    fun glUniform2f(location: Int, x: Float, y: Float) = uniformFloats(location, listOf(x, y))
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = uniformFloats(location, listOf(x, y, z, w))
    private fun uniformFloats(location: Int, values: List<Float>) {
        val state = GlProbe.state(); state.uniformFloats[state.program to location] = values
        GlProbe.record("glUniformFloats", "location" to location, "values" to values)
    }
    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, values: FloatArray, offset: Int) {
        val copy = values.slice(offset until offset + count * 16)
        val state = GlProbe.state(); state.matrices[state.program to location] = copy
        GlProbe.record("glUniformMatrix4fv", "location" to location, "count" to count, "transpose" to transpose, "values" to copy)
    }
    fun glGetAttribLocation(program: Int, name: String): Int {
        val names = GlProbe.attributeNames.getOrPut(program) { mutableMapOf() }
        val location = names.getOrPut(name) { when (name) { "position" -> 0; "coordinate" -> 1; else -> 16 + names.size } }
        GlProbe.record("glGetAttribLocation", "program" to program, "name" to name, "location" to location)
        return location
    }
    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer) {
        val buffer = pointer as FloatBuffer
        GlProbe.state().pointers[index] = AttributePointer(size, type, normalized, stride, buffer.duplicate(), buffer.position(), buffer.limit())
        GlProbe.record("glVertexAttribPointer", "index" to index, "size" to size, "type" to type,
            "normalized" to normalized, "stride" to stride, "position" to buffer.position())
    }
    fun glEnableVertexAttribArray(index: Int) {
        GlProbe.state().enabledAttributes.add(index); GlProbe.record("glEnableVertexAttribArray", "index" to index)
    }
    fun glDisableVertexAttribArray(index: Int) {
        GlProbe.state().enabledAttributes.remove(index); GlProbe.record("glDisableVertexAttribArray", "index" to index)
    }
    fun glDrawArrays(mode: Int, first: Int, count: Int) = GlProbe.draw(mode, first, count)
    fun glGetError(): Int {
        val state = GlProbe.state(); val error = state.glError; state.glError = GL_NO_ERROR
        GlProbe.record("glGetError", "error" to error)
        return error
    }
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GlProbe.record("glClearColor", "values" to listOf(red, green, blue, alpha))
    }
    fun glClear(mask: Int) = GlProbe.record("glClear", "mask" to mask)
    fun glCreateShader(type: Int): Int {
        val shader = GlProbe.shaderId(); GlProbe.record("glCreateShader", "type" to type, "shader" to shader)
        return shader
    }
    fun glShaderSource(shader: Int, source: String) {
        GlProbe.shaderSources[shader] = source; GlProbe.record("glShaderSource", "shader" to shader, "source" to source)
    }
    fun glCompileShader(shader: Int) {
        GlProbe.shaderCompiled[shader] = !GlProbe.takeFailure("compileShader"); GlProbe.record("glCompileShader", "shader" to shader)
    }
    fun glGetShaderiv(shader: Int, parameter: Int, result: IntArray, offset: Int) {
        result[offset] = if (GlProbe.shaderCompiled[shader] == true) 1 else 0
        GlProbe.record("glGetShaderiv", "shader" to shader, "parameter" to parameter, "value" to result[offset])
    }
    fun glGetShaderInfoLog(shader: Int): String {
        GlProbe.record("glGetShaderInfoLog", "shader" to shader)
        return "Host shader $shader failed to compile"
    }
    fun glCreateProgram(): Int {
        val program = if (GlProbe.takeFailure("createProgram")) 0 else GlProbe.programId()
        if (program != 0) GlProbe.programShaders[program] = mutableListOf()
        GlProbe.record("glCreateProgram", "program" to program)
        return program
    }
    fun glAttachShader(program: Int, shader: Int) {
        if (program != 0) GlProbe.programShaders.getOrPut(program) { mutableListOf() }.add(shader)
        GlProbe.record("glAttachShader", "program" to program, "shader" to shader)
    }
    fun glLinkProgram(program: Int) {
        val injected = GlProbe.takeFailure("linkProgram")
        GlProbe.programLinked[program] = program != 0 && !injected &&
            GlProbe.programShaders[program].orEmpty().all { GlProbe.shaderCompiled[it] == true }
        GlProbe.record("glLinkProgram", "program" to program)
    }
    fun glGetProgramiv(program: Int, parameter: Int, result: IntArray, offset: Int) {
        result[offset] = if (GlProbe.programLinked[program] == true) 1 else 0
        GlProbe.record("glGetProgramiv", "program" to program, "parameter" to parameter, "value" to result[offset])
    }
    fun glGetProgramInfoLog(program: Int): String {
        GlProbe.record("glGetProgramInfoLog", "program" to program)
        return "Host program $program failed to link"
    }
    fun glDeleteShader(shader: Int) { GlProbe.shaderDeleted(shader); GlProbe.record("glDeleteShader", "shader" to shader) }
    fun glDeleteProgram(program: Int) {
        GlProbe.record("glDeleteProgram", "program" to program)
        if (GlProbe.takeFailure("deleteProgram")) error("Injected program delete failure")
        GlProbe.programDeleted(program)
    }
    fun glDeleteTextures(count: Int, textures: IntArray, offset: Int) {
        GlProbe.record("glDeleteTextures", "textures" to textures.slice(offset until offset + count))
        if (GlProbe.takeFailure("deleteTextures")) error("Injected texture delete failure")
        repeat(count) { GlProbe.textureDeleted(textures[offset + it]) }
    }
}
