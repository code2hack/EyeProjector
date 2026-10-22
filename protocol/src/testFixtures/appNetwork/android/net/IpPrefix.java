package android.net;
import java.net.InetAddress;
public class IpPrefix {
 public final InetAddress address; public final int length;
 public IpPrefix(InetAddress address,int length){this.address=address;this.length=length;}
 public InetAddress getAddress(){return address;}
 public int getPrefixLength(){return length;}
 public String toString(){throw new AssertionError("Raw prefix dump accessed");}
}
