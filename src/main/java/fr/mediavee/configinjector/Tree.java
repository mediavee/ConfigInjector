package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigPath.Index;
import fr.mediavee.configinjector.ConfigPath.Key;
import fr.mediavee.configinjector.ConfigPath.Segment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edits a parsed document made of {@link Map}, {@link List} and scalar values.
 */
final class Tree {

    sealed interface Result permits Applied, Failed {
    }

    record Applied(boolean changed) implements Result {
    }

    record Failed(String reason) implements Result {
    }

    private Tree() {
    }

    /**
     * Sets {@code value} at {@code path}. Missing keys are created only when {@code create} is set;
     * an existing value is never replaced by a container to reach a deeper path.
     */
    static Result set(Object root, ConfigPath path, Object value, boolean create) {
        List<Segment> segments = path.segments();
        Object container = root;
        String location = "document root";
        for (int i = 0; i < segments.size(); i++) {
            boolean last = i == segments.size() - 1;
            container = unwrap(container);
            switch (segments.get(i)) {
                case Key(String name) -> {
                    if (!(container instanceof Map<?, ?> raw)) {
                        return new Failed(location + " is a " + typeName(container) + ", not a map");
                    }
                    Map<Object, Object> map = cast(raw);
                    Object key = findKey(map, name);
                    if (key == null && !create) {
                        return new Failed("key '" + name + "' not found under " + location + " (set create: true to add it)");
                    }
                    if (last) {
                        if (key != null && sameValue(map.get(key), value)) {
                            return new Applied(false);
                        }
                        map.put(key == null ? name : key, value);
                        return new Applied(true);
                    }
                    if (key == null) {
                        if (segments.get(i + 1) instanceof Index) {
                            return new Failed("cannot create list '" + name + "' under " + location);
                        }
                        Map<Object, Object> child = new LinkedHashMap<>();
                        map.put(name, child);
                        container = child;
                    } else {
                        container = map.get(key);
                    }
                    location = "'" + name + "'";
                }
                case Index(int position) -> {
                    if (!(container instanceof List<?> raw)) {
                        return new Failed(location + " is a " + typeName(container) + ", not a list");
                    }
                    List<Object> list = cast(raw);
                    if (position >= list.size()) {
                        return new Failed("index [" + position + "] out of range under " + location + " (size " + list.size() + ")");
                    }
                    if (last) {
                        if (sameValue(list.get(position), value)) {
                            return new Applied(false);
                        }
                        list.set(position, value);
                        return new Applied(true);
                    }
                    container = list.get(position);
                    location = "[" + position + "]";
                }
            }
        }
        throw new IllegalStateException("empty path");
    }

    /** Matches keys by their string form: YAML keys may be numbers or booleans. */
    private static Object findKey(Map<Object, Object> map, String name) {
        if (map.containsKey(name)) {
            return name;
        }
        for (Object key : map.keySet()) {
            if (String.valueOf(key).equals(name)) {
                return key;
            }
        }
        return null;
    }

    static Object unwrap(Object node) {
        return node instanceof Format.TomlDocument document ? document.values() : node;
    }

    static boolean sameValue(Object left, Object right) {
        Object a = unwrap(left);
        Object b = unwrap(right);
        // Parsers pick Integer, Long or BigInteger by magnitude; only the written type family matters.
        if (a instanceof Number x && b instanceof Number y && isIntegral(x) == isIntegral(y) && isFinite(x) && isFinite(y)) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!sameValue(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : x.entrySet()) {
                Object other = findKey(cast(y), String.valueOf(entry.getKey()));
                if (other == null || !sameValue(entry.getValue(), y.get(other))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof BigInteger || n instanceof Short || n instanceof Byte;
    }

    private static boolean isFinite(Number n) {
        return !(n instanceof Double d && !Double.isFinite(d)) && !(n instanceof Float f && !Float.isFinite(f));
    }

    static String typeName(Object value) {
        return switch (unwrap(value)) {
            case null -> "null";
            case Map<?, ?> m -> "map";
            case List<?> l -> "list";
            case String s -> "string";
            case Number n -> "number";
            case Boolean b -> "boolean";
            case Object other -> other.getClass().getSimpleName();
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
