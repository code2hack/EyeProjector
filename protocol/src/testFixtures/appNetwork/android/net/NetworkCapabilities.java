package android.net;
import java.util.Set;
public class NetworkCapabilities {
 public static final int TRANSPORT_CELLULAR=0,TRANSPORT_WIFI=1,TRANSPORT_BLUETOOTH=2,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4,TRANSPORT_WIFI_AWARE=5,TRANSPORT_LOWPAN=6,TRANSPORT_USB=8;
 public Set<Integer> flags=Set.of();
 public boolean hasTransport(int flag){return flags.contains(flag);}
 public String toString(){throw new AssertionError("Private transport-info dump accessed");}
}
