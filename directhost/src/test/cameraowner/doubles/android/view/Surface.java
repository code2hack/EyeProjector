package android.view;
import android.os.Parcel;
import android.os.Parcelable;
/** Deliberately artificial one-int Surface payload. It models no native Surface/Binder serializer. */
public final class Surface implements Parcelable {
    public final int fixtureId;
    public volatile int releaseCalls;
    public final java.util.concurrent.atomic.AtomicInteger releaseCount = new java.util.concurrent.atomic.AtomicInteger();
    public volatile RuntimeException releaseFault;
    /** Host-only controlled entry during an in-flight release, never an Android Surface API. */
    public volatile Runnable releaseHook;
    public volatile boolean released;
    public volatile boolean valid = true;
    public Surface() { this(0); }
    public Surface(int value) { fixtureId = value; }
    public boolean isValid() { return valid && !released; }
    public void release() {
        releaseCalls++;
        releaseCount.incrementAndGet();
        if (releaseHook != null) releaseHook.run();
        if (releaseFault != null) throw releaseFault;
        released = true;
        valid = false;
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) { destination.writeInt(fixtureId); }
    public static final Creator<Surface> CREATOR = new Creator<Surface>() {
        @Override public Surface createFromParcel(Parcel source) { return new Surface(source.readInt()); }
        @Override public Surface[] newArray(int size) { return new Surface[size]; }
    };
}
