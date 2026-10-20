package android.net;
public class Network {
 public final long handle;
 public Network(long handle){this.handle=handle;}
 public long getNetworkHandle(){throw new AssertionError("Opaque network identifier accessed");}
 public String toString(){throw new AssertionError("Network dump accessed");}
}
