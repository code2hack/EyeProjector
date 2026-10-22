import android.content.Context;
import android.net.*;
import com.code2hack.eyeprojector.protocol.AppNetworkDiagnostics;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
public class AppNetworkProbe {
 static final class SecretFailure extends SecurityException {
  SecretFailure(){super("HOST_TEST_PRIVATE_SENTINEL");}
  public String getMessage(){throw new AssertionError("Exception message accessed");}
 }
 static void require(boolean condition){if(!condition)throw new AssertionError("Schema/privacy contract failed");}
 static IpPrefix prefix(String address,int length)throws Exception{return new IpPrefix(InetAddress.getByName(address),length);}
 public static void main(String[] args)throws Exception{
  String mode=args[0];Context context=new Context();ConnectivityManager manager=new ConnectivityManager();context.service=manager;
  if(mode.equals("mixed")){
   manager.active=new Network(101);manager.bound=new Network(202);
   NetworkCapabilities capabilities=new NetworkCapabilities();capabilities.flags=Set.of(NetworkCapabilities.TRANSPORT_WIFI,NetworkCapabilities.TRANSPORT_VPN);
   manager.caps.put(manager.active,capabilities);
   LinkProperties link=new LinkProperties();link.name="wlan\"\n0";
   link.routes=List.of(new RouteInfo(prefix("192.0.2.0",24)),new RouteInfo(prefix("2001:db8::",32)));manager.links.put(manager.active,link);
  }else if(mode.equals("errors")){
   manager.activeFailure=new SecretFailure();manager.bound=new Network(202);manager.capFailures.put(manager.bound,new SecretFailure());
   LinkProperties link=new LinkProperties();link.interfaceFailure=new SecretFailure();RouteInfo bad=new RouteInfo(prefix("192.0.2.0",24));bad.missingApi=true;
   link.routes=List.of(bad,new RouteInfo(prefix("198.51.100.0",24)));manager.links.put(manager.bound,link);
  }else if(mode.equals("many-routes")){
   manager.active=new Network(303);LinkProperties link=new LinkProperties();link.name="wlan0";
   link.routes=java.util.Collections.nCopies(100,new RouteInfo(prefix("192.0.2.0",24)));manager.links.put(manager.active,link);
  }else if(mode.equals("service-null"))context.service=null;
  else if(mode.equals("service-error"))context.failure=new SecretFailure();
  else if(!mode.equals("nulls"))throw new AssertionError("Unknown mode");
  String report=AppNetworkDiagnostics.capture(context);
  require(!report.contains("HOST_TEST_PRIVATE_SENTINEL"));
  require(report.startsWith("{\"schema\":1,\"service\":"));
  if(mode.equals("nulls"))require(report.equals("{\"schema\":1,\"service\":\"PRESENT\",\"activeDefault\":{\"selection\":\"NULL\",\"transports\":null,\"linkProperties\":null},\"processBound\":{\"selection\":\"NULL\",\"transports\":null,\"linkProperties\":null},\"errors\":{}}"));
  if(mode.equals("mixed")){
   require(!report.contains("handle"));
   require(report.contains("\"WIFI\":true")&&report.contains("\"VPN\":true")&&report.contains("\"CELLULAR\":false"));
   require(report.contains("wlan\\\"\\u000a0"));require(report.contains("192.0.2.0")&&report.contains("2001:db8:"));
   require(report.contains("\"errors\":{}"));
  }
  if(mode.equals("errors")){
   require(report.contains("\"ACTIVE_SELECT\":\"SecretFailure\""));
   require(report.contains("\"BOUND_CAPABILITIES\":\"SecretFailure\""));
   require(report.contains("\"BOUND_INTERFACE\":\"SecretFailure\""));
   require(report.contains("\"BOUND_PREFIX\":\"NoSuchMethodError\""));require(report.contains("198.51.100.0"));
  }
  if(mode.startsWith("service-"))require(report.contains("\"selection\":\"UNAVAILABLE\""));
  if(mode.equals("service-error"))require(report.contains("\"SERVICE\":\"SecretFailure\""));
  if(mode.equals("many-routes")){
   require(report.contains("\"routesTruncated\":true"));require(report.split("192.0.2.0",-1).length-1==64);
  }
  System.out.println("JSON "+report);System.out.println("PASS "+mode);
 }
}
