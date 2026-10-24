package com.code2hack.eyeprojector.cameraaccess;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

/** One serial EGL producer per service-owned input Surface. All methods run on renderer. */
final class SyntheticStream implements AutoCloseable {
    private final int id;
    private final Surface surface;
    private final int width;
    private final int height;
    private final Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private long frames;
    private long requests;
    private int lastRequest;
    private boolean closed;
    private final Runnable tick = this::draw;

    SyntheticStream(int id, Surface surface, int width, int height, Handler handler) {
        this.id = id; this.surface = surface; this.width = width; this.height = height; this.handler = handler;
    }

    void start() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            require(EGL14.eglInitialize(display, versions, 0, versions, 1), "initialize");
            int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0, "config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(!context.equals(EGL14.EGL_NO_CONTEXT), "context");
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
            require(!window.equals(EGL14.EGL_NO_SURFACE), "window");
            handler.post(tick);
        } catch (RuntimeException error) { close(); throw error; }
    }

    private void draw() {
        if (closed) return;
        try {
            require(EGL14.eglMakeCurrent(display, window, window, context), "makeCurrent");
            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            // Slow background phase and moving white bar make preview changes visible.
            float phase = (frames % 120) / 120f;
            GLES20.glClearColor(phase, 1f - phase, 0.25f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor((int) (frames * 7 % width), height / 4, Math.max(1, width / 16), height / 2);
            GLES20.glClearColor(1f, 1f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // Bottom binary frame marker survives JPEG; each bit is a 40-pixel tile at VGA.
            for (int bit = 0; bit < 16; bit++) {
                GLES20.glScissor(bit * width / 16, 0, width / 16, height / 8);
                float value = ((frames >> bit) & 1) == 0 ? 0f : 1f;
                GLES20.glClearColor(value, value, value, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "draw");
            require(EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime()), "presentationTime");
            require(EGL14.eglSwapBuffers(display, window), "swapBuffers");
            frames++;
            if (frames == 1 || frames % 30 == 0) report();
            handler.postDelayed(tick, 34);
        } catch (RuntimeException error) {
            CameraAccessMain.event("producer_error", "streamId", id, "error", error.toString());
            close();
        }
    }

    void captureRequested(int frameId) { requests++; lastRequest = frameId; }
    void report() {
        CameraAccessMain.event("producer_progress", "streamId", id, "postedFrames", frames,
                "captureRequests", requests, "lastRequest", lastRequest, "closed", closed);
    }
    private static void require(boolean ok, String operation) {
        if (!ok) throw new IllegalStateException(operation + " EGL error 0x" + Integer.toHexString(EGL14.eglGetError()));
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacks(tick);
        if (!display.equals(EGL14.EGL_NO_DISPLAY)) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (!window.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, window);
            if (!context.equals(EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
        surface.release();
        report();
    }
}
