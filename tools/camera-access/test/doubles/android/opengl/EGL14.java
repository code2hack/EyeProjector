package android.opengl;
import android.view.Surface;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
public final class EGL14 {
    public static final int EGL_DEFAULT_DISPLAY = 0, EGL_RENDERABLE_TYPE = 1,
        EGL_OPENGL_ES2_BIT = 2, EGL_SURFACE_TYPE = 3, EGL_WINDOW_BIT = 4,
        EGL_RED_SIZE = 5, EGL_GREEN_SIZE = 6, EGL_BLUE_SIZE = 7,
        EGL_ALPHA_SIZE = 8, EGL_NONE = 0x3038, EGL_CONTEXT_CLIENT_VERSION = 9;
    public static final EGLDisplay EGL_NO_DISPLAY = new EGLDisplay();
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext();
    public static final EGLSurface EGL_NO_SURFACE = new EGLSurface();
    public static final Map<String, RuntimeException> faults = new HashMap<>();
    public static final Set<String> falseResults = new HashSet<>();
    public static final Map<String, Integer> calls = new HashMap<>();
    private static int error = 0x3000;
    public static void reset() { faults.clear(); falseResults.clear(); calls.clear(); error = 0x3000; }
    public static boolean step(String operation) {
        calls.merge(operation, 1, Integer::sum);
        if (faults.containsKey(operation)) { error = 0x300d; throw faults.get(operation); }
        if (falseResults.contains(operation)) { error = 0x300d; return false; }
        return true;
    }
    public static EGLDisplay eglGetDisplay(int display) { return step("eglGetDisplay") ? new EGLDisplay() : EGL_NO_DISPLAY; }
    public static boolean eglInitialize(EGLDisplay display, int[] major, int majorOffset, int[] minor, int minorOffset) {
        boolean ok = step("eglInitialize"); major[majorOffset] = 1; minor[minorOffset] = 5; return ok;
    }
    public static boolean eglChooseConfig(EGLDisplay display, int[] attrs, int offset, EGLConfig[] configs,
            int configOffset, int size, int[] count, int countOffset) {
        boolean ok = step("eglChooseConfig"); configs[configOffset] = new EGLConfig(); count[countOffset] = ok ? 1 : 0; return ok;
    }
    public static EGLContext eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext shared, int[] attrs, int offset) {
        return step("eglCreateContext") ? new EGLContext() : EGL_NO_CONTEXT;
    }
    public static EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object window, int[] attrs, int offset) {
        if (!step("eglCreateWindowSurface")) return EGL_NO_SURFACE;
        if (window == null) throw new IllegalArgumentException("DOUBLE_NULL_NATIVE_WINDOW");
        if (!(window instanceof Surface) || !((Surface) window).isValid())
            throw new IllegalArgumentException("DOUBLE_INVALID_NATIVE_WINDOW");
        return new EGLSurface();
    }
    public static boolean eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) {
        return step("eglMakeCurrent");
    }
    public static boolean eglSwapBuffers(EGLDisplay display, EGLSurface window) { return step("eglSwapBuffers"); }
    public static boolean eglDestroySurface(EGLDisplay display, EGLSurface window) { return step("eglDestroySurface"); }
    public static boolean eglDestroyContext(EGLDisplay display, EGLContext context) { return step("eglDestroyContext"); }
    public static boolean eglTerminate(EGLDisplay display) { return step("eglTerminate"); }
    public static boolean eglReleaseThread() { return step("eglReleaseThread"); }
    public static int eglGetError() { int value = error; error = 0x3000; return value; }
}
