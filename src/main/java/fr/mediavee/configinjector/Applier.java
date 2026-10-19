package fr.mediavee.configinjector;

import fr.mediavee.configinjector.Overlay.Assignment;
import fr.mediavee.configinjector.Overlay.Edits;
import fr.mediavee.configinjector.Overlay.FileEdit;
import fr.mediavee.configinjector.Overlay.RawFile;
import fr.mediavee.configinjector.Tree.Applied;
import fr.mediavee.configinjector.Tree.Failed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies an overlay in two phases: every edit is resolved in memory first, and files are written
 * only when no edit of any file failed. A write failing midway rolls back the files already written.
 */
final class Applier {

    /** {@code original} is empty when the file does not exist yet. */
    record PlannedWrite(String name, Path path, Optional<String> original, String content) {
    }

    @FunctionalInterface
    interface FileWriter {
        void write(Path path, String content) throws IOException;
    }

    private Applier() {
    }

    /** Resolves every edit; returns the files whose content changes. Writes nothing. */
    static List<PlannedWrite> plan(Path root, Overlay overlay, Placeholders placeholders, List<String> problems) {
        List<PlannedWrite> writes = new ArrayList<>();
        for (FileEdit edit : overlay.files()) {
            Optional<String> symlink = firstSymlink(root, edit.path());
            if (symlink.isPresent()) {
                problems.add(edit.name() + ": symlink not allowed: " + symlink.get());
                continue;
            }
            Optional<PlannedWrite> write = switch (edit) {
                case RawFile raw -> planRaw(raw, placeholders, problems);
                case Edits edits -> planEdits(edits, root.relativize(edits.path()), overlay.forbidden(), placeholders, problems);
            };
            write.ifPresent(writes::add);
        }
        return writes;
    }

    static void write(List<PlannedWrite> writes, List<String> problems) {
        write(writes, problems, Applier::atomicWrite);
    }

    /** Checks every target is writable, then writes them all, restoring the originals if one fails. */
    static void write(List<PlannedWrite> writes, List<String> problems, FileWriter writer) {
        for (PlannedWrite write : writes) {
            if (!Files.isWritable(write.path().getParent())) {
                problems.add(write.name() + ": directory is not writable");
            } else if (write.original().isPresent() && !Files.isWritable(write.path())) {
                problems.add(write.name() + ": file is not writable");
            }
        }
        if (!problems.isEmpty()) {
            return;
        }
        List<PlannedWrite> done = new ArrayList<>();
        for (PlannedWrite write : writes) {
            try {
                writer.write(write.path(), write.content());
                done.add(write);
            } catch (IOException e) {
                problems.add(write.name() + ": write failed: " + e);
                rollback(done.reversed(), problems);
                return;
            }
        }
    }

    private static void rollback(List<PlannedWrite> done, List<String> problems) {
        for (PlannedWrite write : done) {
            try {
                if (write.original().isPresent()) {
                    atomicWrite(write.path(), write.original().get());
                } else {
                    Files.deleteIfExists(write.path());
                }
                problems.add(write.name() + ": rolled back");
            } catch (IOException e) {
                problems.add(write.name() + ": ROLLBACK FAILED, file left with overlay values: " + e);
            }
        }
    }

    /** Returns the first symlink on the way from {@code root} to {@code path}, the target included. */
    private static Optional<String> firstSymlink(Path root, Path path) {
        Path current = root;
        for (Path name : root.relativize(path)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                return Optional.of(root.relativize(current).toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<PlannedWrite> planRaw(RawFile raw, Placeholders placeholders, List<String> problems) {
        List<String> missing = new ArrayList<>();
        String content = (String) placeholders.resolve(raw.content(), missing);
        missing.forEach(placeholder -> problems.add(raw.name() + ": unresolved " + placeholder));
        if (!Files.isDirectory(raw.path().getParent(), LinkOption.NOFOLLOW_LINKS)) {
            problems.add(raw.name() + ": parent directory does not exist");
            return Optional.empty();
        }
        Optional<String> original;
        try {
            original = Files.exists(raw.path(), LinkOption.NOFOLLOW_LINKS)
                    ? Optional.of(Files.readString(raw.path(), StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            problems.add(raw.name() + ": cannot read: " + e);
            return Optional.empty();
        }
        if (!missing.isEmpty() || original.map(content::equals).orElse(false)) {
            return Optional.empty();
        }
        return Optional.of(new PlannedWrite(raw.name(), raw.path(), original, content));
    }

    private static Optional<PlannedWrite> planEdits(Edits edits, Path relative, Overlay.Forbidden forbidden, Placeholders placeholders,
                                                    List<String> problems) {
        String name = edits.name();
        if (!Files.isRegularFile(edits.path(), LinkOption.NOFOLLOW_LINKS)) {
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
            problems.add(name + ": " + edits.format().describeError(e));
            return Optional.empty();
        }

        int before = problems.size();
        boolean changed = false;
        List<Splice.Edit> applied = new ArrayList<>();
        for (Assignment assignment : edits.assignments()) {
            String where = name + ": " + assignment.path() + ": ";
            List<String> missing = new ArrayList<>();
            Object value = placeholders.resolve(assignment.value(), missing);
            if (!missing.isEmpty()) {
                missing.forEach(placeholder -> problems.add(where + "unresolved " + placeholder));
                continue;
            }
            Tree.Result result;
            ConfigPath path = null;
            if (properties != null) {
                result = properties.set(assignment.path(), value, assignment.create());
            } else {
                try {
                    path = ConfigPath.parse(assignment.path());
                    result = Tree.set(tree, path, value, assignment.create());
                } catch (IllegalArgumentException e) {
                    result = new Failed(e.getMessage());
                }
            }
            switch (result) {
                case Applied(boolean valueChanged) -> {
                    changed |= valueChanged;
                    if (valueChanged && path != null) {
                        applied.add(new Splice.Edit(path, value));
                    }
                }
                case Failed(String reason) -> problems.add(where + reason);
            }
        }
        if (!changed || problems.size() > before) {
            return Optional.empty();
        }
        String content;
        try {
            if (properties != null) {
                content = properties.text();
            } else {
                Object edited = tree;
                // A kept comment may hold a production value; the whole rewrite drops comments.
                content = Splice.apply(edits.format(), original, applied, edited)
                        .filter(text -> !ForbiddenScanner.flags(forbidden, relative, text))
                        .orElseGet(() -> edits.format().write(edited));
            }
        } catch (RuntimeException e) {
            // Writer messages may quote values; the exception type is enough to locate the edit.
            problems.add(name + ": cannot write as " + edits.format() + " (" + e.getClass().getSimpleName() + ")");
            return Optional.empty();
        }
        return content.equals(original)
                ? Optional.empty()
                : Optional.of(new PlannedWrite(name, edits.path(), Optional.of(original), content));
    }

    static void atomicWrite(Path file, String content) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
            if (view != null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                preserveOwnership(view, attributes);
                view.setPermissions(attributes.permissions());
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void preserveOwnership(PosixFileAttributeView view, PosixFileAttributes original) {
        try {
            view.setGroup(original.group());
        } catch (IOException e) {
            // Only possible when the writer is not in the file's group; the mode is still preserved.
        }
        try {
            view.setOwner(original.owner());
        } catch (IOException e) {
            // Changing the owner needs privileges the server process normally lacks; same fallback.
        }
    }
}
