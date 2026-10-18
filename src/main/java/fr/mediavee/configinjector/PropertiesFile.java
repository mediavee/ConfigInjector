package fr.mediavee.configinjector;

import fr.mediavee.configinjector.Tree.Applied;
import fr.mediavee.configinjector.Tree.Failed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Line-based editor for {@code .properties} files: only the edited values change, so the order,
 * comments and escaping of every other line stay as the server wrote them.
 */
final class PropertiesFile {

    private record Entry(int line, String key, int keyEnd, int valueStart, boolean continued) {
    }

    private final List<String> lines;
    private final String newline;

    private PropertiesFile(List<String> lines, String newline) {
        this.lines = lines;
        this.newline = newline;
    }

    static PropertiesFile parse(String text) {
        return new PropertiesFile(new ArrayList<>(Arrays.asList(text.split("\r?\n", -1))),
                text.contains("\r\n") ? "\r\n" : "\n");
    }

    String text() {
        return String.join(newline, lines);
    }

    /** Sets the property named exactly {@code key}; property keys are never split on dots. */
    Tree.Result set(String key, Object value, boolean create) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return new Failed("properties values must be scalars, got a " + Tree.typeName(value));
        }
        String text = value == null ? "" : String.valueOf(value);
        Entry entry = null;
        for (Entry candidate : entries()) {
            if (candidate.key().equals(key)) {
                entry = candidate;
            }
        }
        if (entry == null) {
            if (!create) {
                return new Failed("key '" + key + "' not found (set create: true to add it)");
            }
            int end = lines.getLast().isEmpty() ? lines.size() - 1 : lines.size();
            lines.add(end, escape(key, true) + "=" + escape(text, false));
            return new Applied(true);
        }
        if (entry.continued()) {
            return new Failed("key '" + key + "' has a multi-line value, which is not supported");
        }
        String line = lines.get(entry.line());
        if (unescape(line.substring(entry.valueStart())).equals(text)) {
            return new Applied(false);
        }
        // A key alone on its line has no separator yet: "key" + "value" would rename the key.
        String prefix = entry.valueStart() == entry.keyEnd() ? line.substring(0, entry.keyEnd()) + "=" : line.substring(0, entry.valueStart());
        lines.set(entry.line(), prefix + escape(text, false));
        return new Applied(true);
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        boolean continuation = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean wasContinuation = continuation;
            int start = skipWhitespace(line, 0);
            boolean comment = start == line.length() || line.charAt(start) == '#' || line.charAt(start) == '!';
            continuation = !comment && endsWithOddBackslashes(line);
            if (wasContinuation || comment) {
                continue;
            }
            int end = start;
            while (end < line.length()) {
                char c = line.charAt(end);
                if (c == '\\') {
                    end += 2;
                    continue;
                }
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            end = Math.min(end, line.length());
            int valueStart = skipWhitespace(line, end);
            if (valueStart < line.length() && (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) {
                valueStart = skipWhitespace(line, valueStart + 1);
            }
            entries.add(new Entry(i, unescape(line.substring(start, end)), end, valueStart, continuation));
        }
        return entries;
    }

    private static int skipWhitespace(String line, int from) {
        int i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\f')) {
            i++;
        }
        return i;
    }

    private static boolean endsWithOddBackslashes(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 == raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < raw.length() && raw.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        out.append('u');
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static String escape(String text, boolean key) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                case '=', ':', '#', '!' -> out.append(key ? "\\" + c : String.valueOf(c));
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
