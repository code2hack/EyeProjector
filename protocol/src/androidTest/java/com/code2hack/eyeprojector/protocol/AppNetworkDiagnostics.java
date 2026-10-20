package com.code2hack.eyeprojector.protocol;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Isolated-test observations only. These getters neither select a network nor describe socket routing.
 * Null default can mean absent/blocked; null process binding means no explicit binding.
 * Selections and properties are separate, non-atomic reads and may disappear between calls. */
public final class AppNetworkDiagnostics {
    private static final int ROUTE_PREFIX_LIMIT = 64;
    private AppNetworkDiagnostics() {}

    public static String capture(Context context) {
        Map<String, String> errors = new LinkedHashMap<>();
        Read<ConnectivityManager> service = read("SERVICE", () ->
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE), errors);
        Map<String, Object> report = object("schema", 1, "service", service.status);
        report.put("activeDefault", selected(service.value, true, errors));
        report.put("processBound", selected(service.value, false, errors));
        report.put("errors", errors);
        return json(report);
    }

    private static Map<String, Object> selected(ConnectivityManager manager, boolean active,
                                                Map<String, String> errors) {
        String site = active ? "ACTIVE" : "BOUND";
        Read<Network> selection = manager == null ? new Read<>("UNAVAILABLE", null) :
                read(site + "_SELECT", () -> active ? manager.getActiveNetwork() :
                        manager.getBoundNetworkForProcess(), errors);
        Network network = selection.value;
        Map<String, Object> report = object("selection", selection.status,
                "transports", null, "linkProperties", null);
        if (network == null) return report;
        Read<NetworkCapabilities> capabilities = read(site + "_CAPABILITIES", () ->
                manager.getNetworkCapabilities(network), errors);
        Map<String, Object> transports = object("status", capabilities.status, "flags", null);
        if (capabilities.value != null) {
            Map<String, Object> flags = new LinkedHashMap<>();
            // Fixed API32-compatible set; no capabilities/transport-info/specifier toString().
            flag(flags, "CELLULAR", NetworkCapabilities.TRANSPORT_CELLULAR, capabilities.value, site, errors);
            flag(flags, "WIFI", NetworkCapabilities.TRANSPORT_WIFI, capabilities.value, site, errors);
            flag(flags, "BLUETOOTH", NetworkCapabilities.TRANSPORT_BLUETOOTH, capabilities.value, site, errors);
            flag(flags, "ETHERNET", NetworkCapabilities.TRANSPORT_ETHERNET, capabilities.value, site, errors);
            flag(flags, "VPN", NetworkCapabilities.TRANSPORT_VPN, capabilities.value, site, errors);
            flag(flags, "WIFI_AWARE", NetworkCapabilities.TRANSPORT_WIFI_AWARE, capabilities.value, site, errors);
            flag(flags, "LOWPAN", NetworkCapabilities.TRANSPORT_LOWPAN, capabilities.value, site, errors);
            flag(flags, "USB", NetworkCapabilities.TRANSPORT_USB, capabilities.value, site, errors);
            transports.put("flags", flags);
        }
        report.put("transports", transports);
        Read<LinkProperties> properties = read(site + "_LINK_PROPERTIES", () ->
                manager.getLinkProperties(network), errors);
        Map<String, Object> link = object("status", properties.status, "interfaceName", null,
                "routePrefixes", null, "routePrefixLimit", ROUTE_PREFIX_LIMIT, "routesTruncated", null);
        if (properties.value != null) {
            link.put("interfaceName", read(site + "_INTERFACE", properties.value::getInterfaceName, errors).value);
            Read<List<RouteInfo>> routes = read(site + "_ROUTES", properties.value::getRoutes, errors);
            if (routes.value != null) {
                List<Object> prefixes = new ArrayList<>();
                // Report destinations only: no gateway, DNS, Wi-Fi info or raw route dumps.
                read(site + "_PREFIXES", () -> {
                    link.put("routesTruncated", routes.value.size() > ROUTE_PREFIX_LIMIT);
                    int examined = 0;
                    for (RouteInfo route : routes.value) {
                        if (examined++ == ROUTE_PREFIX_LIMIT) break;
                        Map<String, Object> prefix = read(site + "_PREFIX", () -> {
                            IpPrefix destination = route.getDestination();
                            return object("address", destination.getAddress().getHostAddress(),
                                    "prefixLength", destination.getPrefixLength());
                        }, errors).value;
                        if (prefix != null) prefixes.add(prefix);
                    }
                    return true;
                }, errors);
                link.put("routePrefixes", prefixes);
            }
        }
        report.put("linkProperties", link);
        return report;
    }

    private static void flag(Map<String, Object> flags, String name, int transport,
                             NetworkCapabilities capabilities, String site, Map<String, String> errors) {
        flags.put(name, read(site + "_TRANSPORT_" + name, () -> capabilities.hasTransport(transport), errors).value);
    }

    private static final class Read<T> {
        final String status;
        final T value;
        Read(String status, T value) { this.status = status; this.value = value; }
    }

    private static <T> Read<T> read(String site, Supplier<T> getter, Map<String, String> errors) {
        try {
            T value = getter.get();
            return new Read<>(value == null ? "NULL" : "PRESENT", value);
        } catch (RuntimeException | LinkageError failure) {
            errors.put(site, failure.getClass().getSimpleName());
            return new Read<>("ERROR", null);
        }
    }

    private static Map<String, Object> object(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder encoded = new StringBuilder("\"");
            for (char c : ((String) value).toCharArray()) {
                if (c == '"' || c == '\\') encoded.append('\\').append(c);
                else if (c < 32) encoded.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                else encoded.append(c);
            }
            return encoded.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Integer) return value.toString();
        StringBuilder encoded = new StringBuilder(value instanceof Map ? "{" : "[");
        boolean separator = false;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (separator) encoded.append(',');
                encoded.append(json(entry.getKey())).append(':').append(json(entry.getValue()));
                separator = true;
            }
        } else {
            for (Object entry : (Iterable<?>) value) {
                if (separator) encoded.append(',');
                encoded.append(json(entry));
                separator = true;
            }
        }
        return encoded.append(value instanceof Map ? '}' : ']').toString();
    }
}
