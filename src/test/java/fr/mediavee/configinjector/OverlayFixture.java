package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigInjector.Mode;
import fr.mediavee.configinjector.ConfigInjector.Outcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** A server root in a temporary directory with an overlay file next to it. */
record OverlayFixture(Path root, Path overlay) {

    static OverlayFixture in(Path temp) {
        try {
            Path root = Files.createDirectories(temp.resolve("server"));
            return new OverlayFixture(root, temp.resolve("overlay.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    OverlayFixture file(String name, String content) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return this;
    }

    String read(String name) throws IOException {
        return Files.readString(root.resolve(name));
    }

    Outcome apply(String overlayYaml) throws IOException {
        return run(overlayYaml, Mode.APPLY, Map.of());
    }

    Outcome run(String overlayYaml, Mode mode, Map<String, String> environment) throws IOException {
        Files.writeString(overlay, overlayYaml);
        return ConfigInjector.run(overlay, root, mode, environment);
    }
}
