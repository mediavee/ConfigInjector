package fr.mediavee.configinjector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${secret:NAME}} (secrets file, then environment) and {@code ${env:NAME}}
 * (environment only) inside overlay values.
 */
record Placeholders(Map<String, String> secrets, Map<String, String> environment) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(secret|env):([^}]*)}");
    private static final Set<PosixFilePermission> GROUP_OR_OTHERS = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    static Placeholders load(Optional<Path> secretsFile, Map<String, String> environment, List<String> problems) {
        Map<String, String> secrets = new LinkedHashMap<>();
        if (secretsFile.isEmpty()) {
            return new Placeholders(secrets, environment);
        }
        Path file = secretsFile.get();
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null && posix.readAttributes().permissions().stream().anyMatch(GROUP_OR_OTHERS::contains)) {
                problems.add("secrets file " + file + " is accessible by group or others (chmod 600 it)");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    problems.add("secrets file " + file + ":" + (i + 1) + ": expected KEY=VALUE");
                    continue;
                }
                secrets.put(line.substring(0, equals).strip(), line.substring(equals + 1));
            }
        } catch (IOException e) {
            problems.add("cannot read secrets file " + file + ": " + e);
        }
        return new Placeholders(secrets, environment);
    }

    /** Returns {@code value} with every placeholder in its strings resolved; unresolved names go to {@code missing}. */
    Object resolve(Object value, List<String> missing) {
        return switch (value) {
            case String text -> resolveString(text, missing);
            case List<?> list -> {
                List<Object> resolved = new ArrayList<>(list.size());
                list.forEach(element -> resolved.add(resolve(element, missing)));
                yield resolved;
            }
            case Map<?, ?> map -> {
                Map<Object, Object> resolved = new LinkedHashMap<>();
                map.forEach((key, element) -> resolved.put(key, resolve(element, missing)));
                yield resolved;
            }
            case null, default -> value;
        };
    }

    private String resolveString(String text, List<String> missing) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(2);
            String resolved = matcher.group(1).equals("secret")
                    ? secrets.getOrDefault(name, environment.get(name))
                    : environment.get(name);
            if (resolved == null) {
                missing.add(matcher.group());
                resolved = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
