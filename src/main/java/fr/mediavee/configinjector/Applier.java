package fr.mediavee.configinjector;

import fr.mediavee.configinjector.Overlay.Assignment;
import fr.mediavee.configinjector.Overlay.Edits;
import fr.mediavee.configinjector.Overlay.FileEdit;
import fr.mediavee.configinjector.Overlay.RawFile;
import fr.mediavee.configinjector.Tree.Applied;
import fr.mediavee.configinjector.Tree.Failed;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies an overlay in two phases: every edit is resolved in memory first, and files are written
 * only when no edit of any file failed.
 */
final class Applier {

    record PlannedWrite(String name, Path path, String content) {
    }

    private Applier() {
    }

    /** Resolves every edit; returns the files whose content changes. Writes nothing. */
    static List<PlannedWrite> plan(Overlay overlay, Placeholders placeholders, List<String> problems) {
        List<PlannedWrite> writes = new ArrayList<>();
        for (FileEdit edit : overlay.files()) {
            Optional<PlannedWrite> write = switch (edit) {
                case RawFile raw -> planRaw(raw, placeholders, problems);
                case Edits edits -> planEdits(edits, placeholders, problems);
            };
            write.ifPresent(writes::add);
        }
        return writes;
    }

    /** Writes every planned file atomically; stops at the first failure. */
    static void write(List<PlannedWrite> writes, List<String> problems) {
        for (PlannedWrite write : writes) {
            try {
                atomicWrite(write.path(), write.content());
            } catch (IOException e) {
                problems.add(write.name() + ": write failed: " + e);
                return;
            }
        }
    }

    private static Optional<PlannedWrite> planRaw(RawFile raw, Placeholders placeholders, List<String> problems) {
        List<String> missing = new ArrayList<>();
        String content = (String) placeholders.resolve(raw.content(), missing);
        missing.forEach(placeholder -> problems.add(raw.name() + ": unresolved " + placeholder));
        if (!Files.isDirectory(raw.path().getParent())) {
            problems.add(raw.name() + ": parent directory does not exist");
            return Optional.empty();
        }
        try {
            if (Files.exists(raw.path()) && Files.readString(raw.path(), StandardCharsets.UTF_8).equals(content)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            problems.add(raw.name() + ": cannot read: " + e);
            return Optional.empty();
        }
        return missing.isEmpty() ? Optional.of(new PlannedWrite(raw.name(), raw.path(), content)) : Optional.empty();
    }

    private static Optional<PlannedWrite> planEdits(Edits edits, Placeholders placeholders, List<String> problems) {
        String name = edits.name();
        if (!Files.isRegularFile(edits.path())) {
            problems.add(name + ": file not found");
            return Optional.empty();
        }
        String original;
        try {
            original = Files.readString(edits.path(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(name + ": cannot read: " + e);
            return Optional.empty();
        }

        PropertiesFile properties = null;
        Object tree = null;
        try {
            if (edits.format() == Format.PROPERTIES) {
                properties = PropertiesFile.parse(original);
            } else {
                tree = edits.format().parse(original);
            }
        } catch (RuntimeException e) {
            problems.add(name + ": cannot parse as " + edits.format() + ": " + parseError(e));
            return Optional.empty();
        }

        int before = problems.size();
        boolean changed = false;
        for (Assignment assignment : edits.assignments()) {
            String where = name + ": " + assignment.path() + ": ";
            List<String> missing = new ArrayList<>();
            Object value = placeholders.resolve(assignment.value(), missing);
            if (!missing.isEmpty()) {
                missing.forEach(placeholder -> problems.add(where + "unresolved " + placeholder));
                continue;
            }
            Tree.Result result;
            if (properties != null) {
                result = properties.set(assignment.path(), value, assignment.create());
            } else {
                try {
                    result = Tree.set(tree, ConfigPath.parse(assignment.path()), value, assignment.create());
                } catch (IllegalArgumentException e) {
                    result = new Failed(e.getMessage());
                }
            }
            switch (result) {
                case Applied(boolean valueChanged) -> changed |= valueChanged;
                case Failed(String reason) -> problems.add(where + reason);
            }
        }
        if (!changed || problems.size() > before) {
            return Optional.empty();
        }
        String content;
        try {
            content = properties != null ? properties.text() : edits.format().write(tree);
        } catch (RuntimeException e) {
            problems.add(name + ": cannot write as " + edits.format() + ": " + Overlay.oneLine(e.getMessage()));
            return Optional.empty();
        }
        return content.equals(original) ? Optional.empty() : Optional.of(new PlannedWrite(name, edits.path(), content));
    }

    /** SnakeYAML messages quote the offending line, which may hold a production secret. */
    private static String parseError(RuntimeException e) {
        if (e instanceof MarkedYAMLException yaml && yaml.getProblemMark() != null) {
            return yaml.getProblem() + " at line " + (yaml.getProblemMark().getLine() + 1)
                    + ", column " + (yaml.getProblemMark().getColumn() + 1);
        }
        return Overlay.oneLine(e.getMessage());
    }

    static void atomicWrite(Path target, String content) throws IOException {
        Path file = Files.exists(target) ? target.toRealPath() : target;
        Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            if (Files.exists(file) && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file));
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
