package android.opengl;
public final class GLES20 {
    public static final int GL_SCISSOR_TEST = 1, GL_COLOR_BUFFER_BIT = 2, GL_NO_ERROR = 0;
    public static void glViewport(int x, int y, int width, int height) { EGL14.step("glViewport"); }
    public static void glDisable(int flag) { EGL14.step("glDisable"); }
    public static void glEnable(int flag) { EGL14.step("glEnable"); }
    public static void glClearColor(float r, float g, float b, float a) { EGL14.step("glClearColor"); }
    public static void glClear(int mask) { EGL14.step("glClear"); }
    public static void glScissor(int x, int y, int width, int height) { EGL14.step("glScissor"); }
    public static int glGetError() { return EGL14.step("glGetError") ? GL_NO_ERROR : 1; }
}
