package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Typed-cell fixture with byte-position accounting and backfill. It is NOT Android Parcel bytes.
 * Assumptions: int/boolean4, long8, Binder-reference8 (an arbitrary fixture slot), UTF16-like
 * string accounting, and byte-array payload rounded to4. Interface policy headers are omitted.
 * Binder values remain in-process object references; there are no native handles, FDs or IPC.
 */
public final class Parcel {
    public static final class Cell {
        public final int offset;
        public final int width;
        public final String kind;
        public final Object value;
        private Cell(int start, int bytes, String type, Object data) {
            offset = start; width = bytes; kind = type;
            value = data instanceof byte[] ? ((byte[]) data).clone() : data;
        }
    }
    private final TreeMap<Integer, Cell> cells = new TreeMap<>();
    private final List<String> operations = new ArrayList<>();
    private int position;
    private int size;
    public static Parcel obtain() { return new Parcel(); }
    public static Parcel obtain(IBinder ignored) { return new Parcel(); }
    public void recycle() { operations.add("recycle"); }
    public int dataPosition() { return position; }
    public int dataSize() { return size; }
    public int dataAvail() { return size - position; }
    public void setDataPosition(int value) {
        if (value < 0 || value > size) throw new BadParcelableException("Fixture position outside recorded data");
        position = value;
    }
    public List<Cell> fixtureCells() { return new ArrayList<>(cells.values()); }
    public List<String> fixtureOperations() { return new ArrayList<>(operations); }
    private static int align4(int value) { return (value + 3) & ~3; }
    private void put(String kind, int width, Object value) {
        cells.subMap(position, true, position + width, false).clear();
        cells.put(position, new Cell(position, width, kind, value));
        position += width; size = Math.max(size, position);
    }
    private Object get(String kind) {
        Cell cell = cells.get(position);
        if (cell == null || !kind.equals(cell.kind))
            throw new BadParcelableException("Fixture expected " + kind + " at " + position);
        position += cell.width;
        return cell.value;
    }
    public void writeInterfaceToken(String value) {
        operations.add("writeInterfaceToken"); put("token", 4 + align4((value.length() + 1) * 2), value);
    }
    public void enforceInterface(String expected) {
        operations.add("enforceInterface");
        if (!expected.equals(get("token"))) throw new SecurityException("Fixture interface token mismatch");
    }
    public void enforceNoDataAvail() {
        if (dataAvail() != 0) throw new BadParcelableException("Fixture has remaining argument cells");
    }
    public void writeInt(int value) { operations.add("writeInt"); put("int", 4, value); }
    public int readInt() { operations.add("readInt"); return (Integer) get("int"); }
    public void writeLong(long value) { operations.add("writeLong"); put("long", 8, value); }
    public long readLong() { operations.add("readLong"); return (Long) get("long"); }
    public void writeBoolean(boolean value) { operations.add("writeBoolean"); writeInt(value ? 1 : 0); }
    public boolean readBoolean() { operations.add("readBoolean"); return readInt() != 0; }
    public void writeString(String value) {
        operations.add("writeString"); put("string", value == null ? 4 : 4 + align4((value.length() + 1) * 2), value);
    }
    public String readString() { operations.add("readString"); return (String) get("string"); }
    public void writeStrongBinder(IBinder value) { operations.add("writeStrongBinder"); put("binder", 8, value); }
    public IBinder readStrongBinder() { operations.add("readStrongBinder"); return (IBinder) get("binder"); }
    public void writeStrongInterface(IInterface value) {
        operations.add("writeStrongInterface"); writeStrongBinder(value == null ? null : value.asBinder());
    }
    public <T extends Parcelable> void writeTypedObject(T value, int flags) {
        if (value == null) writeInt(0);
        else { writeInt(1); value.writeToParcel(this, flags); }
    }
    public <T> T readTypedObject(Parcelable.Creator<T> creator) {
        return readInt() == 0 ? null : creator.createFromParcel(this);
    }
    public <T extends Parcelable> void writeTypedArray(T[] values, int flags) {
        if (values == null) { writeInt(-1); return; }
        writeInt(values.length);
        for (T value : values) writeTypedObject(value, flags);
    }
    public <T> T[] createTypedArray(Parcelable.Creator<T> creator) {
        int length = readInt();
        if (length < 0) return null;
        T[] values = creator.newArray(length);
        for (int i = 0; i < length; i++) values[i] = readTypedObject(creator);
        return values;
    }
    public void writeByteArray(byte[] values) {
        operations.add("writeByteArray");
        if (values == null) { writeInt(-1); return; }
        writeInt(values.length);
        if (values.length != 0) put("bytes", align4(values.length), values);
    }
    public byte[] createByteArray() {
        operations.add("createByteArray"); int length = readInt();
        if (length < 0) return null;
        if (length == 0) return new byte[0];
        byte[] value = (byte[]) get("bytes");
        if (value.length != length) throw new BadParcelableException("Fixture array length mismatch");
        return value.clone();
    }
    public void writeNoException() { writeInt(0); }
    public void readException() throws RemoteException {
        if (readInt() != 0) throw new RemoteException("Fixture remote exception status");
    }
}
