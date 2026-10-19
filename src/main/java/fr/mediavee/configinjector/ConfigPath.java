package fr.mediavee.configinjector;

import java.util.ArrayList;
import java.util.List;

/**
 * A path into a parsed document: {@code a.b[0]["dotted.key"].c}.
 */
record ConfigPath(String text, List<Segment> segments) {

    sealed interface Segment permits Key, Index {
    }

    record Key(String name) implements Segment {
    }

    record Index(int position) implements Segment {
    }

    static ConfigPath parse(String text) {
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == '[') {
                int close;
                if (i + 1 < length && text.charAt(i + 1) == '"') {
                    StringBuilder name = new StringBuilder();
                    int j = i + 2;
                    while (j < length && text.charAt(j) != '"') {
                        if (text.charAt(j) == '\\' && j + 1 < length) {
                            j++;
                        }
                        name.append(text.charAt(j++));
                    }
                    if (j + 1 >= length || text.charAt(j + 1) != ']') {
                        throw new IllegalArgumentException("unterminated [\"...\"] in path '" + text + "'");
                    }
                    segments.add(new Key(name.toString()));
                    close = j + 1;
                } else {
                    close = text.indexOf(']', i);
                    if (close < 0) {
                        throw new IllegalArgumentException("unterminated [ in path '" + text + "'");
                    }
                    String index = text.substring(i + 1, close);
                    if (!index.matches("\\d+")) {
                        throw new IllegalArgumentException("invalid list index [" + index + "] in path '" + text + "'");
                    }
                    segments.add(new Index(Integer.parseInt(index)));
                }
                i = close + 1;
                if (i < length && text.charAt(i) != '.' && text.charAt(i) != '[') {
                    throw new IllegalArgumentException("expected '.' or '[' after ']' in path '" + text + "'");
                }
                if (i < length && text.charAt(i) == '.') {
                    i++;
                    if (i == length) {
                        throw new IllegalArgumentException("path '" + text + "' ends with '.'");
                    }
                }
            } else {
                int end = i;
                while (end < length && text.charAt(end) != '.' && text.charAt(end) != '[') {
                    end++;
                }
                if (end == i) {
                    throw new IllegalArgumentException("empty key in path '" + text + "'");
                }
                segments.add(new Key(text.substring(i, end)));
                i = end;
                if (i < length && text.charAt(i) == '.') {
                    i++;
                    if (i == length) {
                        throw new IllegalArgumentException("path '" + text + "' ends with '.'");
                    }
                }
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        return new ConfigPath(text, List.copyOf(segments));
    }

    @Override
    public String toString() {
        return text;
    }
}
