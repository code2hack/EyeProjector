package android.opengl;
public final class EGLExt {
    public static boolean eglPresentationTimeANDROID(EGLDisplay display, EGLSurface window, long timestamp) {
        return EGL14.step("eglPresentationTimeANDROID");
    }
}
