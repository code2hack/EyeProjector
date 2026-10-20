package android.net;
import java.util.List;
public class LinkProperties {
 public String name;public List<RouteInfo> routes=List.of();public RuntimeException interfaceFailure;
 public String getInterfaceName(){if(interfaceFailure!=null)throw interfaceFailure;return name;}
 public List<RouteInfo> getRoutes(){return routes;}
 public String toString(){throw new AssertionError("Private link-property dump accessed");}
}
