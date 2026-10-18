package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigInjector.Mode;
import fr.mediavee.configinjector.ConfigInjector.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyTest {

    @TempDir
    Path temp;

    @Test
    void rawReplacesTheWholeFileFromASecret() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("forwarding.secret", "prod-secret");
        Path secrets = secretsFile("VELOCITY_FORWARDING_SECRET=dev-secret\n");
        Outcome outcome = fixture.apply("""
                secrets: %s
                files:
                  forwarding.secret: {raw: "${secret:VELOCITY_FORWARDING_SECRET}"}
                  created.txt: {raw: "new"}
                """.formatted(secrets));
        assertEquals(List.of(), outcome.problems());
        assertEquals("dev-secret", fixture.read("forwarding.secret"));
        assertEquals("new", fixture.read("created.txt"));
    }

    @Test
    void secretsResolveFromFileThenEnvironment() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "sql:\n  password: prod\n  user: prod\n  url: prod\n");
        Path secrets = secretsFile("# comment\nDB_PASSWORD=from=file\n");
        Outcome outcome = fixture.run("""
                secrets: %s
                files:
                  config.yml:
                    - {path: sql.password, value: "${secret:DB_PASSWORD}"}
                    - {path: sql.user, value: "${secret:DB_USER}"}
                    - {path: sql.url, value: "jdbc://${env:DB_HOST}/db"}
                """.formatted(secrets), Mode.APPLY, Map.of("DB_USER", "dev", "DB_HOST", "172.18.0.1", "DB_PASSWORD", "from-env"));
        assertEquals(List.of(), outcome.problems());
        assertEquals("sql:\n  password: from=file\n  user: dev\n  url: jdbc://172.18.0.1/db\n", fixture.read("config.yml"));
    }

    @Test
    void unresolvedPlaceholdersAreReportedTogether() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "a: prod\nb: prod\n");
        Outcome outcome = fixture.apply("""
                files:
                  config.yml:
                    - {path: a, value: "${secret:MISSING_A}"}
                    - {path: b, value: "${env:MISSING_B}"}
                """);
        assertEquals(List.of("config.yml: a: unresolved ${secret:MISSING_A}", "config.yml: b: unresolved ${env:MISSING_B}"),
                outcome.problems());
        assertEquals("a: prod\nb: prod\n", fixture.read("config.yml"));
    }

    @Test
    void secretsFileReadableByOthersIsRejected() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "a: 1\n");
        Path secrets = secretsFile("A=1\n");
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-r--r--"));
        Outcome outcome = fixture.apply("secrets: %s\nfiles:\n  config.yml:\n    - {path: a, value: 2}\n".formatted(secrets));
        assertEquals(List.of("secrets file " + secrets + " is accessible by group or others (chmod 600 it)"), outcome.problems());
    }

    @Test
    void nothingIsWrittenWhenAnyEditFails() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp)
                .file("a.yml", "key: prod\n")
                .file("b.json", "{\"key\": \"prod\"}");
        Outcome outcome = fixture.apply("""
                files:
                  a.yml:
                    - {path: key, value: dev}
                  b.json:
                    - {path: key, value: dev}
                    - {path: typo, value: dev}
                  missing.yml:
                    - {path: key, value: dev}
                """);
        assertEquals(List.of("b.json: typo: key 'typo' not found under document root (set create: true to add it)",
                "missing.yml: file not found"), outcome.problems());
        assertEquals("key: prod\n", fixture.read("a.yml"));
        assertEquals("{\"key\": \"prod\"}", fixture.read("b.json"));
    }

    @Test
    void applyingTwiceChangesNothingTheSecondTime() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "a: prod # comment\nb: 1\n");
        Files.setPosixFilePermissions(fixture.root().resolve("config.yml"), PosixFilePermissions.fromString("rw-r-----"));
        String overlay = "files:\n  config.yml:\n    - {path: a, value: dev}\n";
        assertEquals(1, fixture.apply(overlay).written());
        String first = fixture.read("config.yml");
        Path file = fixture.root().resolve("config.yml");
        Files.setLastModifiedTime(file, FileTime.fromMillis(0));

        Outcome second = fixture.apply(overlay);
        assertEquals(List.of(), second.problems());
        assertEquals(0, second.written());
        assertEquals(first, fixture.read("config.yml"));
        assertEquals(FileTime.fromMillis(0), Files.getLastModifiedTime(file));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        try (var files = Files.list(fixture.root())) {
            assertEquals(List.of("config.yml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void checkModeNeverWrites() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "a: prod\n");
        Outcome outcome = fixture.run("files:\n  config.yml:\n    - {path: a, value: dev}\n", Mode.CHECK, Map.of());
        assertEquals(List.of(), outcome.problems());
        assertEquals("a: prod\n", fixture.read("config.yml"));
    }

    @Test
    void filesOutsideTheRootAreRejected() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp);
        Outcome outcome = fixture.apply("files:\n  ../escape.yml: {raw: x}\n");
        assertEquals(List.of("../escape.yml: file path must be relative to the server root"), outcome.problems());
        assertFalse(Files.exists(temp.resolve("escape.yml")));
        assertTrue(Files.exists(fixture.overlay()));
    }

    private Path secretsFile(String content) throws IOException {
        Path secrets = temp.resolve("secrets.env");
        Files.writeString(secrets, content);
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"));
        return secrets;
    }
}
