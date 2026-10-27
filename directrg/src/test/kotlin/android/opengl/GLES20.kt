@file:Suppress("UNUSED_PARAMETER")
package android.opengl

import java.nio.Buffer
object GLES11Ext { const val GL_TEXTURE_EXTERNAL_OES = 1 }
object GLES20 {
    const val GL_TEXTURE_MIN_FILTER = 2
    const val GL_TEXTURE_MAG_FILTER = 3
    const val GL_LINEAR = 4
    const val GL_TEXTURE_WRAP_S = 5
    const val GL_TEXTURE_WRAP_T = 6
    const val GL_CLAMP_TO_EDGE = 7
    const val GL_TEXTURE0 = 8
    const val GL_FLOAT = 9
    const val GL_TRIANGLE_STRIP = 10
    const val GL_NO_ERROR = 0
    const val GL_VERTEX_SHADER = 11
    const val GL_FRAGMENT_SHADER = 12
    const val GL_COMPILE_STATUS = 13
    const val GL_LINK_STATUS = 14
    const val GL_SCISSOR_TEST = 0x0C11
    @JvmStatic fun glGenTextures(n: Int, names: IntArray, offset: Int) { names[offset] = 1 }
    @JvmStatic fun glBindTexture(target: Int, value: Int) = Unit
    @JvmStatic fun glTexParameteri(target: Int, key: Int, value: Int) = Unit
    @JvmStatic fun glViewport(x: Int, y: Int, w: Int, h: Int) = Unit
    @JvmStatic fun glUseProgram(id: Int) = Unit
    @JvmStatic fun glActiveTexture(id: Int) = Unit
    @JvmStatic fun glUniform1i(id: Int, v: Int) = Unit
    @JvmStatic fun glGetUniformLocation(p: Int, name: String) = 0
    @JvmStatic fun glUniformMatrix4fv(id: Int, count: Int, transpose: Boolean, matrix: FloatArray, offset: Int) = Unit
    @JvmStatic fun glGetAttribLocation(p: Int, name: String) = 0
    @JvmStatic fun glVertexAttribPointer(id: Int, size: Int, type: Int, normalized: Boolean, stride: Int, data: Buffer) = Unit
    @JvmStatic fun glEnableVertexAttribArray(id: Int) = Unit
    @JvmStatic fun glDisableVertexAttribArray(index: Int) = Unit
    @JvmStatic fun glEnable(cap: Int) = Unit
    @JvmStatic fun glScissor(x: Int, y: Int, width: Int, height: Int) = Unit
    @JvmStatic fun glDisable(cap: Int) = Unit
    @JvmStatic fun glDrawArrays(type: Int, first: Int, count: Int) = Unit
    @JvmStatic fun glGetError() = 0
    @JvmStatic fun glCreateShader(type: Int) = 1
    @JvmStatic fun glShaderSource(id: Int, source: String) = Unit
    @JvmStatic fun glCompileShader(id: Int) = Unit
    @JvmStatic fun glGetShaderiv(id: Int, parameter: Int, status: IntArray, offset: Int) { status[offset] = 1 }
    @JvmStatic fun glGetShaderInfoLog(id: Int) = ""
    @JvmStatic fun glDeleteShader(id: Int) = Unit
    @JvmStatic fun glCreateProgram() = 1
    @JvmStatic fun glAttachShader(p: Int, s: Int) = Unit
    @JvmStatic fun glLinkProgram(p: Int) = Unit
    @JvmStatic fun glGetProgramiv(id: Int, parameter: Int, status: IntArray, offset: Int) { status[offset] = 1 }
    @JvmStatic fun glGetProgramInfoLog(id: Int) = ""
    @JvmStatic fun glDeleteProgram(id: Int) = Unit
    @JvmStatic fun glDeleteTextures(n: Int, names: IntArray, offset: Int) = Unit
}
