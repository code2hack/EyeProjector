package android.net;
public class RouteInfo {
 public IpPrefix prefix;public boolean missingApi;
 public RouteInfo(IpPrefix prefix){this.prefix=prefix;}
 public IpPrefix getDestination(){if(missingApi)throw new NoSuchMethodError("HOST_TEST_PRIVATE_SENTINEL");return prefix;}
 public String toString(){throw new AssertionError("Raw route dump accessed");}
}
