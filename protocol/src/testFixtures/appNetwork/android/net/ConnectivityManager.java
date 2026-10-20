package android.net;
import java.util.HashMap;
import java.util.Map;
public class ConnectivityManager {
 public Network active,bound;public RuntimeException activeFailure,boundFailure;
 public final Map<Network,NetworkCapabilities> caps=new HashMap<>();
 public final Map<Network,LinkProperties> links=new HashMap<>();
 public final Map<Network,RuntimeException> capFailures=new HashMap<>(),linkFailures=new HashMap<>();
 public Network getActiveNetwork(){if(activeFailure!=null)throw activeFailure;return active;}
 public Network getBoundNetworkForProcess(){if(boundFailure!=null)throw boundFailure;return bound;}
 public NetworkCapabilities getNetworkCapabilities(Network network){if(capFailures.containsKey(network))throw capFailures.get(network);return caps.get(network);}
 public LinkProperties getLinkProperties(Network network){if(linkFailures.containsKey(network))throw linkFailures.get(network);return links.get(network);}
}
