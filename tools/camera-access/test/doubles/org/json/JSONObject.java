package org.json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class JSONObject {
    public static final Object NULL = new Object();
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private boolean recorded;
    public JSONObject put(String key, Object value) throws JSONException {
        if (key == null) throw new JSONException("Null key");
        fields.put(key, value == NULL ? null : value); return this;
    }
    public static synchronized void reset() { EVENTS.clear(); }
    public static synchronized List<Map<String, Object>> events() { return new ArrayList<>(EVENTS); }
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    @Override public String toString() {
        synchronized (JSONObject.class) {
            if (!recorded) { EVENTS.add(new LinkedHashMap<>(fields)); recorded = true; }
        }
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (out.length() > 1) out.append(',');
            out.append(quote(field.getKey())).append(':');
            Object value = field.getValue();
            out.append(value == null ? "null" : value instanceof Number || value instanceof Boolean
                    ? value.toString() : quote(value.toString()));
        }
        return out.append('}').toString();
    }
}
