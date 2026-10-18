package fr.mediavee.configinjector;

import fr.mediavee.configinjector.it.ServerStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Launches a real JVM with the built agent jar in front of a stub server main. */
class AgentTest {

    private record Run(int exitCode, String output) {
    }

    @TempDir
    Path temp;

    @Test
    void appliesTheOverlayBeforeTheServerMainRuns() throws Exception {
        Path root = server("sql:\n  password: prod\n");
        Path overlay = overlay("files:\n  config.yml:\n    - {path: sql.password, value: dev}\n");

        Run run = launch(root, overlay);

        assertEquals(0, run.exitCode(), run.output());
        assertEquals("sql:\n  password: dev\n", Files.readString(root.resolve("main-ran")));
        assertTrue(run.output().contains("configinjector: overlay OK, 1 file(s) written"), run.output());
    }

    @Test
    void forbiddenValueExitsWithOneAndTheServerMainNeverRuns() throws Exception {
        Path root = server("sql:\n  host: prod.example.net\n");
        Path overlay = overlay("forbidden:\n  contains: [prod.example.net]\n");

        Run run = launch(root, overlay);

        assertEquals(1, run.exitCode(), run.output());
        assertFalse(Files.exists(root.resolve("main-ran")));
        assertTrue(run.output().contains("configinjector: config.yml:2: value matches forbidden.contains[0] (at sql.host)"), run.output());
        assertFalse(run.output().replace("contains: [prod.example.net]", "").contains("prod.example.net"), run.output());
    }

    private Path server(String config) throws IOException {
        Path root = Files.createDirectories(temp.resolve("server"));
        Files.writeString(root.resolve("config.yml"), config);
        return root;
    }

    private Path overlay(String content) throws IOException {
        Path overlay = temp.resolve("overlay.yml");
        Files.writeString(overlay, content);
        return overlay;
    }

    private static Run launch(Path root, Path overlay) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String agent = System.getProperty("configinjector.jar");
        String classes = Path.of(ServerStub.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(List.of(java, "-javaagent:" + agent + "=" + overlay, "-cp", classes, ServerStub.class.getName()))
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child JVM timed out");
        return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
