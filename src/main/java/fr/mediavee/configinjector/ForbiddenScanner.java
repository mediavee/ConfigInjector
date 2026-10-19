package fr.mediavee.configinjector;

import fr.mediavee.configinjector.Overlay.Forbidden;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Looks for production values left in the server's files. It fails closed: a file it cannot
 * inspect (symlink, special file, over the size limit, unreadable) is a problem, not a skip.
 * Reports name the file, line and rule, never the value that matched.
 */
final class ForbiddenScanner {

    /**
     * Path globs with the {@link java.nio.file.FileSystem#getPathMatcher} syntax, except that
     * {@code **}{@code /} also matches no directory, as in most tools.
     */
    record Glob(Pattern pattern, Optional<Pattern> directory) {

        static Glob of(String glob) {
            Optional<Pattern> directory = glob.endsWith("/**")
                    ? Optional.of(regex(glob.substring(0, glob.length() - 3)))
                    : Optional.empty();
            return new Glob(regex(glob), directory);
        }

        private static Pattern regex(String glob) {
            StringBuilder regex = new StringBuilder();
            int braces = 0;
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                if (glob.startsWith("**/", i)) {
                    regex.append("(?:.*/)?");
                    i += 3;
                    continue;
                }
                if (glob.startsWith("**", i)) {
                    regex.append(".*");
                    i += 2;
                    continue;
                }
                if (c == '[' && glob.indexOf(']', i + 2) > 0) {
                    int close = glob.indexOf(']', i + 2);
                    String members = glob.substring(i + 1, close);
                    boolean negated = members.startsWith("!");
                    regex.append(negated ? "[^/" : "[");
                    for (char member : (negated ? members.substring(1) : members).toCharArray()) {
                        regex.append(member == '-' || Character.isLetterOrDigit(member) ? String.valueOf(member) : "\\" + member);
                    }
                    regex.append(negated ? "]" : "&&[^/]]");
                    i = close + 1;
                    continue;
                }
                if (c == '\\' && i + 1 < glob.length()) {
                    regex.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                    i += 2;
                    continue;
                }
                switch (c) {
                    case '*' -> regex.append("[^/]*");
                    case '?' -> regex.append("[^/]");
                    case '{' -> {
                        regex.append("(?:");
                        braces++;
                    }
                    case '}' -> {
                        regex.append(')');
                        braces--;
                    }
                    case ',' -> regex.append(braces > 0 ? "|" : ",");
                    default -> regex.append(Pattern.quote(String.valueOf(c)));
                }
                i++;
            }
            return Pattern.compile(regex.toString());
        }

        boolean matches(Path relative) {
            return pattern.matcher(slashes(relative)).matches();
        }

        boolean coversDirectory(Path relative) {
            return directory.map(d -> d.matcher(slashes(relative)).matches()).orElse(false);
        }

        private static String slashes(Path relative) {
            return relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
        }
    }

    private final Forbidden rules;
    private final Map<String, Integer> hashIndex = new LinkedHashMap<>();
    private final Map<String, String> hits = new LinkedHashMap<>();
    private final MessageDigest sha256;
    private int hitCount;

    private ForbiddenScanner(Forbidden rules) {
        this.rules = rules;
        for (int i = 0; i < rules.sha256().size(); i++) {
            hashIndex.putIfAbsent(rules.sha256().get(i), i);
        }
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    /** Scans {@code root}, skipping {@code ignored} files; returns the number of files scanned. */
    static int scan(Path root, Forbidden rules, Set<Path> ignored, List<String> problems) {
        if (rules.isEmpty()) {
            return 0;
        }
        ForbiddenScanner scanner = new ForbiddenScanner(rules);
        List<Glob> include = rules.scan().stream().map(Glob::of).toList();
        List<Glob> exclude = rules.exclude().stream().map(Glob::of).toList();
        int[] scanned = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    Path relative = root.relativize(dir);
                    boolean excluded = !dir.equals(root) && exclude.stream().anyMatch(glob -> glob.coversDirectory(relative));
                    return excluded ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Path relative = root.relativize(file);
                    boolean excluded = exclude.stream().anyMatch(glob -> glob.matches(relative) || glob.coversDirectory(relative));
                    boolean included = !excluded && include.stream().anyMatch(glob -> glob.matches(relative));
                    if (ignored.contains(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.isSymbolicLink()) {
                        // The walk does not follow links, so a linked file or directory would go unchecked.
                        if (included || (!excluded && Files.isDirectory(file))) {
                            problems.add(relative + ": symlink not allowed in the scanned tree, exclude it explicitly");
                        }
                    } else if (!included) {
                        return FileVisitResult.CONTINUE;
                    } else if (!attributes.isRegularFile()) {
                        problems.add(relative + ": not a regular file, exclude it explicitly");
                    } else if (attributes.size() > rules.maxFileSize()) {
                        problems.add(relative + ": larger than forbidden.max-file-size (" + attributes.size()
                                + " bytes), exclude it explicitly or raise the limit");
                    } else {
                        scanner.scanFile(relative, Files.readAllBytes(file));
                        scanned[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    problems.add(root.relativize(file) + ": cannot scan: " + e);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            problems.add("scan of " + root + " failed: " + e);
        }
        problems.addAll(scanner.hits.values());
        return scanned[0];
    }

    private void scanFile(Path relative, byte[] bytes) {
        String text = decode(bytes);
        // Single-value files such as forwarding.secret are hashed whole, exactly and trimmed.
        checkHash(relative, 1, candidates(text), null);
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            checkContains(relative, i + 1, line, null);
            Set<String> candidates = candidates(line);
            int separator = firstSeparator(line);
            if (separator >= 0) {
                candidates.addAll(candidates(line.substring(0, separator)));
                candidates.addAll(candidates(line.substring(separator + 1)));
            }
            checkHash(relative, i + 1, candidates, null);
        }
        Optional<Format> format = Format.byExtension(relative);
        if (format.isEmpty()) {
            return;
        }
        if (format.get() == Format.HOCON) {
            hoconLiterals(text).forEach(literal -> {
                checkContains(relative, literal.line(), literal.text(), null);
                checkHash(relative, literal.line(), candidates(literal.text()), null);
            });
        }
        Object tree;
        try {
            tree = format.get().parseForScan(text);
        } catch (RuntimeException e) {
            // The line scan above already covered every byte; parsing only decodes escapes and nesting.
            return;
        }
        walk(relative, lines, tree, "");
    }

    private record Literal(int line, String text) {
    }

    /**
     * Escape-decoded HOCON strings, read from the text so that values the parser cannot resolve
     * are still checked: every quoted string, and every run of concatenated values with its
     * substitutions dropped ({@code "prod-"${suffix}} yields {@code prod-}, then {@code "a"${x}"b"} yields {@code ab}).
     */
    private static List<Literal> hoconLiterals(String text) {
        List<Literal> literals = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int line = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? text.length() : end;
                String literal = text.substring(i + 3, end);
                literals.add(new Literal(line, literal));
                run.append(literal);
                line += (int) literal.chars().filter(ch -> ch == '\n').count();
                i = Math.min(text.length(), end + 3);
            } else if (c == '"') {
                StringBuilder literal = new StringBuilder();
                i++;
                while (i < text.length() && text.charAt(i) != '"' && text.charAt(i) != '\n') {
                    char ch = text.charAt(i++);
                    if (ch == '\\' && i < text.length()) {
                        char escaped = text.charAt(i++);
                        switch (escaped) {
                            case 'n' -> literal.append('\n');
                            case 't' -> literal.append('\t');
                            case 'r' -> literal.append('\r');
                            case 'b' -> literal.append('\b');
                            case 'f' -> literal.append('\f');
                            case 'u' -> {
                                if (i + 4 <= text.length() && text.substring(i, i + 4).matches("[0-9a-fA-F]{4}")) {
                                    literal.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                                    i += 4;
                                } else {
                                    literal.append('u');
                                }
                            }
                            default -> literal.append(escaped);
                        }
                    } else {
                        literal.append(ch);
                    }
                }
                i++;
                literals.add(new Literal(line, literal.toString()));
                run.append(literal);
            } else if (text.startsWith("${", i)) {
                int end = text.indexOf('}', i);
                i = end < 0 ? text.length() : end + 1;
            } else if (c == '#' || text.startsWith("//", i)) {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else {
                if ("\n=:{}[],".indexOf(c) >= 0) {
                    if (!run.toString().isBlank()) {
                        literals.add(new Literal(line, run.toString()));
                    }
                    run.setLength(0);
                    if (c == '\n') {
                        line++;
                    }
                } else {
                    run.append(c);
                }
                i++;
            }
        }
        if (!run.toString().isBlank()) {
            literals.add(new Literal(line, run.toString()));
        }
        return literals;
    }

    /** Honours a UTF-16 or UTF-8 byte order mark; anything else decodes as UTF-8 with replacement. */
    private static String decode(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return decode(bytes, 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return decode(bytes, 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return decode(bytes, 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String decode(byte[] bytes, int offset, Charset charset) {
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void walk(Path relative, String[] lines, Object node, String path) {
        switch (Tree.unwrap(node)) {
            case null -> {
            }
            case Map<?, ?> map -> map.forEach((key, value) -> {
                String detail = path.isEmpty() ? "a top-level key" : "a key under " + path;
                // A forbidden key must not appear in the report through the paths below it either.
                String segment = checkScalar(relative, lines, String.valueOf(key), detail) ? "<forbidden key>" : String.valueOf(key);
                walk(relative, lines, value, path.isEmpty() ? segment : path + "." + segment);
            });
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    walk(relative, lines, list.get(i), path + "[" + i + "]");
                }
            }
            case Format.YamlMapping mapping -> mapping.entries().forEach(entry -> {
                String detail = path.isEmpty() ? "a top-level key" : "a key under " + path;
                boolean forbidden;
                String key;
                if (entry.getKey() instanceof Format.YamlScalar(String value, int line)) {
                    key = value;
                    forbidden = checkScalar(relative, line, value, detail);
                } else {
                    key = "<complex key>";
                    walk(relative, lines, entry.getKey(), path);
                    forbidden = false;
                }
                String segment = forbidden ? "<forbidden key>" : key;
                walk(relative, lines, entry.getValue(), path.isEmpty() ? segment : path + "." + segment);
            });
            case Format.YamlScalar(String value, int line) -> checkScalar(relative, line, value, "at " + path);
            case Object scalar -> checkScalar(relative, lines, String.valueOf(scalar), "at " + path);
        }
    }

    /** Returns whether {@code value} matched a rule. */
    private boolean checkScalar(Path relative, String[] lines, String value, String detail) {
        return checkScalar(relative, lineOf(lines, value), value, detail);
    }

    private boolean checkScalar(Path relative, int line, String value, String detail) {
        int before = hitCount;
        checkContains(relative, line, value, detail);
        checkHash(relative, line, candidates(value), detail);
        return hitCount > before;
    }

    private void checkContains(Path relative, int line, String text, String detail) {
        for (int i = 0; i < rules.contains().size(); i++) {
            if (text.contains(rules.contains().get(i))) {
                hit(relative, line, "forbidden.contains[" + i + "]", detail);
            }
        }
    }

    private void checkHash(Path relative, int line, Set<String> candidates, String detail) {
        if (hashIndex.isEmpty()) {
            return;
        }
        for (String candidate : candidates) {
            String hash = HexFormat.of().formatHex(sha256.digest(candidate.getBytes(StandardCharsets.UTF_8)));
            Integer index = hashIndex.get(hash);
            if (index != null) {
                hit(relative, line, "forbidden.sha256[" + index + "]", detail);
            }
        }
    }

    /** The exact text and its forms without surrounding whitespace, a trailing comma and quotes. */
    private static Set<String> candidates(String text) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(text);
        String trimmed = text.strip();
        candidates.add(trimmed);
        String withoutComma = trimmed.endsWith(",") ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
        candidates.add(withoutComma);
        candidates.add(unquote(trimmed));
        candidates.add(unquote(withoutComma));
        candidates.remove("");
        return candidates;
    }

    /** {@code detail} locates the match inside a parsed document, or is null for a line match. */
    private void hit(Path relative, int line, String rule, String detail) {
        hitCount++;
        String location = line > 0 ? relative + ":" + line : relative.toString();
        String key = location + " " + rule;
        if (detail == null) {
            hits.putIfAbsent(key, location + ": value matches " + rule);
        } else {
            hits.put(key, location + ": value matches " + rule + " (" + detail + ")");
        }
    }

    private static int lineOf(String[] lines, String value) {
        String first = value.lines().findFirst().orElse(value);
        if (first.isBlank()) {
            return 0;
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(first)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int firstSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0 || colon < 0) {
            return Math.max(equals, colon);
        }
        return Math.min(equals, colon);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
