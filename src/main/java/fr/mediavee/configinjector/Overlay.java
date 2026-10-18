package fr.mediavee.configinjector;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed overlay file. Loading collects every problem instead of stopping at the first one.
 */
record Overlay(Path file, Optional<Path> secrets, List<FileEdit> files, Forbidden forbidden) {

    sealed interface FileEdit permits RawFile, Edits {
        String name();

        Path path();
    }

    /** Replaces the whole file, creating it if absent. */
    record RawFile(String name, Path path, String content) implements FileEdit {
    }

    record Edits(String name, Path path, Format format, List<Assignment> assignments) implements FileEdit {
    }

    record Assignment(String path, Object value, boolean create) {
    }

    record Forbidden(List<String> sha256, List<String> contains, List<String> scan, List<String> exclude, long maxFileSize) {

        static final List<String> DEFAULT_SCAN = List.of("**/*.yml", "**/*.yaml", "**/*.json", "**/*.toml",
                "**/*.properties", "**/*.conf", "**/*.hocon", "**/*.secret", "**/*.txt");
        static final List<String> DEFAULT_EXCLUDE = List.of("logs/**", "cache/**", "world*/**", "libraries/**", "versions/**");
        static final long DEFAULT_MAX_FILE_SIZE = 2L * 1024 * 1024;

        static final Forbidden NONE = new Forbidden(List.of(), List.of(), List.of(), List.of(), 0);

        boolean isEmpty() {
            return sha256.isEmpty() && contains.isEmpty();
        }
    }

    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(B|KB|MB|GB)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    static Overlay load(Path overlayFile, Path root, List<String> problems) {
        Object document;
        try {
            LoaderOptions options = new LoaderOptions();
            // A repeated key would silently drop the first block, such as a whole set of forbidden rules.
            options.setAllowDuplicateKeys(false);
            document = new Yaml(new SafeConstructor(options)).load(Files.readString(overlayFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            problems.add("cannot read overlay " + overlayFile + ": " + e);
            return new Overlay(overlayFile, Optional.empty(), List.of(), Forbidden.NONE);
        } catch (RuntimeException e) {
            problems.add("overlay " + overlayFile + ": " + Format.YAML.describeError(e));
            return new Overlay(overlayFile, Optional.empty(), List.of(), Forbidden.NONE);
        }
        if (!(document instanceof Map<?, ?> top)) {
            problems.add("overlay " + overlayFile + " must be a map with 'files', 'secrets' and 'forbidden'");
            return new Overlay(overlayFile, Optional.empty(), List.of(), Forbidden.NONE);
        }
        checkKeys(top, Set.of("secrets", "files", "forbidden"), "overlay", problems);

        Optional<Path> secrets = Optional.empty();
        if (top.get("secrets") instanceof String path) {
            secrets = Optional.of(root.resolve(path).normalize());
        } else if (top.containsKey("secrets")) {
            problems.add("overlay: 'secrets' must be a file path");
        }

        Map<Path, FileEdit> files = new LinkedHashMap<>();
        if (top.get("files") instanceof Map<?, ?> entries) {
            entries.forEach((name, spec) -> parseFile(String.valueOf(name), spec, root, problems)
                    .ifPresent(edit -> merge(files, edit, problems)));
        } else if (top.containsKey("files")) {
            problems.add("overlay: 'files' must be a map of file path to edits");
        }

        Forbidden forbidden = Forbidden.NONE;
        if (top.get("forbidden") instanceof Map<?, ?> spec) {
            forbidden = parseForbidden(spec, problems);
        } else if (top.containsKey("forbidden")) {
            problems.add("overlay: 'forbidden' must be a map");
        }
        return new Overlay(overlayFile, secrets, List.copyOf(files.values()), forbidden);
    }

    /** Entries naming the same file ({@code a.yml}, {@code ./a.yml}) must apply to a single parse. */
    private static void merge(Map<Path, FileEdit> files, FileEdit edit, List<String> problems) {
        FileEdit existing = files.get(edit.path());
        if (existing == null) {
            files.put(edit.path(), edit);
        } else if (existing instanceof Edits first && edit instanceof Edits second && first.format() == second.format()) {
            List<Assignment> assignments = new ArrayList<>(first.assignments());
            assignments.addAll(second.assignments());
            files.put(edit.path(), new Edits(first.name(), first.path(), first.format(), List.copyOf(assignments)));
        } else {
            problems.add(edit.name() + ": same file as " + existing.name() + ", with a raw replacement or another format");
        }
    }

    private static Optional<FileEdit> parseFile(String name, Object spec, Path root, List<String> problems) {
        Path path = root.resolve(name).normalize();
        if (Path.of(name).isAbsolute() || !path.startsWith(root)) {
            problems.add(name + ": file path must be relative to the server root");
            return Optional.empty();
        }
        Object assignments = spec;
        Optional<Format> format = Format.byExtension(path);
        if (spec instanceof Map<?, ?> map) {
            if (map.containsKey("raw")) {
                checkKeys(map, Set.of("raw"), name, problems);
                if (map.get("raw") instanceof String content) {
                    return Optional.of(new RawFile(name, path, content));
                }
                problems.add(name + ": 'raw' must be a string");
                return Optional.empty();
            }
            checkKeys(map, Set.of("format", "set"), name, problems);
            if (map.containsKey("format")) {
                format = Format.byName(String.valueOf(map.get("format")));
                if (format.isEmpty()) {
                    problems.add(name + ": unknown format '" + map.get("format") + "' (yaml, json, toml, hocon, properties)");
                    return Optional.empty();
                }
            }
            assignments = map.get("set");
        }
        if (format.isEmpty()) {
            problems.add(name + ": unknown format for this extension, add 'format:' (yaml, json, toml, hocon, properties)");
            return Optional.empty();
        }
        if (!(assignments instanceof List<?> list)) {
            problems.add(name + ": expected a list of {path, value} edits, a {format, set} map or {raw: ...}");
            return Optional.empty();
        }
        List<Assignment> parsed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String where = name + " edit #" + (i + 1);
            if (!(list.get(i) instanceof Map<?, ?> edit)) {
                problems.add(where + ": expected {path, value}");
                continue;
            }
            checkKeys(edit, Set.of("path", "value", "blank", "create"), where, problems);
            if (!(edit.get("path") instanceof String editPath) || editPath.isEmpty()) {
                problems.add(where + ": 'path' is required and must be a string");
                continue;
            }
            boolean hasValue = edit.containsKey("value");
            boolean blank = Boolean.TRUE.equals(edit.get("blank"));
            if (hasValue == blank || (edit.containsKey("blank") && !blank)) {
                problems.add(where + " (" + editPath + "): set exactly one of 'value' or 'blank: true'");
                continue;
            }
            Object create = edit.containsKey("create") ? edit.get("create") : Boolean.FALSE;
            if (!(create instanceof Boolean)) {
                problems.add(where + " (" + editPath + "): 'create' must be true or false");
                continue;
            }
            parsed.add(new Assignment(editPath, blank ? "" : edit.get("value"), (Boolean) create));
        }
        return Optional.of(new Edits(name, path, format.get(), List.copyOf(parsed)));
    }

    private static Forbidden parseForbidden(Map<?, ?> spec, List<String> problems) {
        checkKeys(spec, Set.of("sha256", "contains", "scan", "exclude", "max-file-size"), "forbidden", problems);
        List<String> hashes = strings(spec, "sha256", List.of(), problems);
        for (int i = 0; i < hashes.size(); i++) {
            if (!SHA256.matcher(hashes.get(i)).matches()) {
                problems.add("forbidden.sha256[" + i + "] is not a 64-character hex SHA-256");
            }
        }
        List<String> contains = strings(spec, "contains", List.of(), problems);
        if (contains.stream().anyMatch(String::isEmpty)) {
            problems.add("forbidden.contains: empty strings would match every line");
        }
        long maxFileSize = Forbidden.DEFAULT_MAX_FILE_SIZE;
        Object size = spec.get("max-file-size");
        if (size instanceof Number number) {
            maxFileSize = number.longValue();
        } else if (size != null) {
            Matcher matcher = SIZE.matcher(String.valueOf(size).strip());
            if (matcher.matches()) {
                long unit = switch (matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(Locale.ROOT)) {
                    case "KB" -> 1024L;
                    case "MB" -> 1024L * 1024;
                    case "GB" -> 1024L * 1024 * 1024;
                    default -> 1L;
                };
                maxFileSize = Long.parseLong(matcher.group(1)) * unit;
            } else {
                problems.add("forbidden.max-file-size: expected a size such as 2MB, got '" + size + "'");
            }
        }
        if (maxFileSize <= 0) {
            problems.add("forbidden.max-file-size must be positive");
        }
        return new Forbidden(
                hashes.stream().map(hash -> hash.toLowerCase(Locale.ROOT)).toList(),
                contains,
                strings(spec, "scan", Forbidden.DEFAULT_SCAN, problems),
                strings(spec, "exclude", Forbidden.DEFAULT_EXCLUDE, problems),
                maxFileSize);
    }

    private static List<String> strings(Map<?, ?> spec, String key, List<String> fallback, List<String> problems) {
        if (!spec.containsKey(key)) {
            return fallback;
        }
        if (spec.get(key) instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        problems.add("forbidden." + key + " must be a list of strings");
        return fallback;
    }

    private static void checkKeys(Map<?, ?> map, Set<String> allowed, String where, List<String> problems) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                problems.add(where + ": unknown key '" + key + "' (expected " + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }
}
