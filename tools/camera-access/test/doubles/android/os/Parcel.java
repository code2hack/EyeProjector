package android.os;

import java.util.HashMap;
import java.util.Map;

/** Scalar observation fixture only; never represents Android parcel bytes or Binder objects. */
public final class Parcel {
    public int fixtureSize;
    public int fixturePosition;
    public Object fixtureReplyState;
    public int forbiddenOperations;
    public final Map<String, Integer> scalarCalls = new HashMap<>();
    private String failureMethod;
    private int failureCall;
    private Throwable failure;

    public Parcel(int size, int position) { fixtureSize = size; fixturePosition = position; }
    public void failScalar(String method, int call, Throwable value) {
        failureMethod = method; failureCall = call; failure = value;
    }
    private void observe(String method) {
        int call = scalarCalls.merge(method, 1, Integer::sum);
        if (method.equals(failureMethod) && call == failureCall) {
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError("Unsupported scalar fault", failure);
        }
    }
    public int dataSize() { observe("dataSize"); return fixtureSize; }
    public int dataPosition() { observe("dataPosition"); return fixturePosition; }
    public int dataAvail() { observe("dataAvail"); return fixtureSize - fixturePosition; }

    private AssertionError forbidden() {
        forbiddenOperations++;
        return new AssertionError("Payload/cursor operation is outside this scalar fixture");
    }
    public int readInt() { throw forbidden(); }
    public IBinder readStrongBinder() { throw forbidden(); }
    public void enforceInterface(String descriptor) { throw forbidden(); }
    public byte[] marshall() { throw forbidden(); }
    public void appendFrom(Parcel source, int offset, int length) { throw forbidden(); }
    public void setDataPosition(int position) { throw forbidden(); }
    public void setDataSize(int size) { throw forbidden(); }
    public void writeInt(int value) { throw forbidden(); }
    public void writeStrongBinder(IBinder value) { throw forbidden(); }
}
