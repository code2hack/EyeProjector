package android.view;
public final class Surface {
    public boolean valid = true;
    public int releases;
    public RuntimeException releaseFailure;
    public RuntimeException validityFailure;
    public boolean isValid() { if (validityFailure != null) throw validityFailure; return valid; }
    public synchronized void release() {
        releases++;
        if (releaseFailure != null) throw releaseFailure;
        valid = false;
    }
}
