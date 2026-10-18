package fr.mediavee.configinjector;

import fr.mediavee.configinjector.Overlay.Forbidden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Looks for production values left in the server's files. Reports name the file, line and rule,
 * never the value that matched.
 */
final class ForbiddenScanner {

    private record Glob(PathMatcher pattern, Optional<PathMatcher> atRoot, Optional<PathMatcher> directory) {

        static Glob of(String glob) {
            // Java globs need a slash for "**/", so "**/*.yml" would skip files at the root.
            Optional<PathMatcher> atRoot = glob.startsWith("**/") ? Optional.of(matcher(glob.substring(3))) : Optional.empty();
            Optional<PathMatcher> directory = glob.endsWith("/**")
                    ? Optional.of(matcher(glob.substring(0, glob.length() - 3)))
                    : Optional.empty();
            return new Glob(matcher(glob), atRoot, directory);
        }

        private static PathMatcher matcher(String glob) {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }

        boolean matches(Path relative) {
            return pattern.matches(relative) || atRoot.map(m -> m.matches(relative)).orElse(false);
        }

        boolean coversDirectory(Path relative) {
            return directory.map(m -> m.matches(relative)).orElse(false);
        }
    }

    private final Forbidden rules;
    private final Path root;
    private final Map<String, Integer> hashIndex = new LinkedHashMap<>();
    private final Map<String, String> hits = new LinkedHashMap<>();
    private final MessageDigest sha256;

    private ForbiddenScanner(Forbidden rules, Path root) {
        this.rules = rules;
        this.root = root;
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
        ForbiddenScanner scanner = new ForbiddenScanner(rules, root);
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
                    if (attributes.isRegularFile()
                            && attributes.size() <= rules.maxFileSize()
                            && !ignored.contains(file)
                            && include.stream().anyMatch(glob -> glob.matches(relative))
                            && exclude.stream().noneMatch(glob -> glob.matches(relative))) {
                        if (scanner.scanFile(relative, Files.readAllBytes(file))) {
                            scanned[0]++;
                        }
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

    /** Returns false for binary files, which are skipped. */
    private boolean scanFile(Path relative, byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            checkContains(relative, i + 1, line, null);
            String trimmed = line.strip();
            checkHash(relative, i + 1, unquote(trimmed), null);
            int separator = firstSeparator(trimmed);
            if (separator >= 0) {
                checkHash(relative, i + 1, unquote(trimmed.substring(separator + 1)), null);
            }
        }
        Format.byExtension(relative).ifPresent(format -> {
            Object tree;
            try {
                tree = format.parse(String.join("\n", lines));
            } catch (RuntimeException e) {
                // Unparsable files were already covered line by line; scanned files are not ours to validate.
                return;
            }
            walk(relative, lines, tree, "");
        });
        return true;
    }

    private void walk(Path relative, String[] lines, Object node, String path) {
        switch (Tree.unwrap(node)) {
            case null -> {
            }
            case Map<?, ?> map -> map.forEach((key, value) ->
                    walk(relative, lines, value, path.isEmpty() ? String.valueOf(key) : path + "." + key));
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    walk(relative, lines, list.get(i), path + "[" + i + "]");
                }
            }
            case Object scalar -> {
                String value = String.valueOf(scalar);
                int line = lineOf(lines, value);
                checkContains(relative, line, value, path);
                checkHash(relative, line, value, path);
            }
        }
    }

    private void checkContains(Path relative, int line, String text, String path) {
        for (int i = 0; i < rules.contains().size(); i++) {
            if (text.contains(rules.contains().get(i))) {
                hit(relative, line, "forbidden.contains[" + i + "]", path);
            }
        }
    }

    private void checkHash(Path relative, int line, String value, String path) {
        if (hashIndex.isEmpty() || value.isEmpty()) {
            return;
        }
        String hash = HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        Integer index = hashIndex.get(hash);
        if (index != null) {
            hit(relative, line, "forbidden.sha256[" + index + "]", path);
        }
    }

    private void hit(Path relative, int line, String rule, String path) {
        String location = line > 0 ? relative + ":" + line : relative.toString();
        String key = location + " " + rule;
        if (path == null) {
            hits.putIfAbsent(key, location + ": value matches " + rule);
        } else {
            hits.put(key, location + ": value matches " + rule + " (at " + path + ")");
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

    private static String unquote(String text) {
        String value = text.strip();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
