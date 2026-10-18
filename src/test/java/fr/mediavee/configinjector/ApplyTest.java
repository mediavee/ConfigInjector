package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigInjector.Mode;
import fr.mediavee.configinjector.ConfigInjector.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void unwritableTargetIsCaughtBeforeAnyWrite() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("a.yml", "key: prod\n").file("locked/b.yml", "key: prod\n");
        Path locked = fixture.root().resolve("locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Outcome outcome = fixture.apply("files:\n  a.yml:\n    - {path: key, value: dev}\n  locked/b.yml:\n    - {path: key, value: dev}\n");
            assertEquals(List.of("locked/b.yml: directory is not writable"), outcome.problems());
            assertEquals("key: prod\n", fixture.read("a.yml"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void failedWriteRollsBackTheFilesAlreadyWritten() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("a.yml", "key: prod\n").file("b.yml", "key: prod\n");
        Path a = fixture.root().resolve("a.yml");
        Path created = fixture.root().resolve("new.txt");
        List<Applier.PlannedWrite> writes = List.of(
                new Applier.PlannedWrite("a.yml", a, Optional.of("key: prod\n"), "key: dev\n"),
                new Applier.PlannedWrite("new.txt", created, Optional.empty(), "dev"),
                new Applier.PlannedWrite("b.yml", fixture.root().resolve("b.yml"), Optional.of("key: prod\n"), "key: dev\n"));
        List<String> problems = new ArrayList<>();
        Applier.write(writes, problems, (path, content) -> {
            if (path.getFileName().toString().equals("b.yml")) {
                throw new IOException("disk full");
            }
            Applier.atomicWrite(path, content);
        });
        assertEquals(List.of("b.yml: write failed: java.io.IOException: disk full", "new.txt: rolled back", "a.yml: rolled back"), problems);
        assertEquals("key: prod\n", fixture.read("a.yml"));
        assertFalse(Files.exists(created));
    }

    @Test
    void entriesNamingTheSameFileAreAppliedTogether() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "host: prod\nport: 1\n");
        Outcome outcome = fixture.apply("files:\n  config.yml:\n    - {path: host, value: dev}\n  ./config.yml:\n    - {path: port, value: 2}\n");
        assertEquals(List.of(), outcome.problems());
        assertEquals("host: dev\nport: 2\n", fixture.read("config.yml"));
    }

    @Test
    void symlinkedTargetsAreRefused() throws IOException {
        Path prod = Files.writeString(temp.resolve("prod.yml"), "host: prod\n");
        Path prodDir = Files.createDirectories(temp.resolve("prod-plugins"));
        Files.writeString(prodDir.resolve("config.yml"), "host: prod\n");
        OverlayFixture fixture = OverlayFixture.in(temp);
        Files.createSymbolicLink(fixture.root().resolve("config.yml"), prod);
        Files.createSymbolicLink(fixture.root().resolve("plugins"), prodDir);
        Outcome outcome = fixture.apply("files:\n  config.yml:\n    - {path: host, value: dev}\n  plugins/config.yml:\n    - {path: host, value: dev}\n");
        assertEquals(List.of("config.yml: symlink not allowed: config.yml", "plugins/config.yml: symlink not allowed: plugins"),
                outcome.problems());
        assertEquals("host: prod\n", Files.readString(prod));
        assertEquals("host: prod\n", Files.readString(prodDir.resolve("config.yml")));
    }

    @Test
    void duplicateOverlayKeysAreErrors() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp);
        Outcome outcome = fixture.apply("forbidden:\n  contains: [prod]\nforbidden:\n  contains: [other]\n");
        assertEquals(List.of("overlay " + fixture.overlay() + ": duplicate key at line 3, column 1"), outcome.problems());
    }

    @Test
    void keepsTheFileGroup() throws IOException, InterruptedException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.yml", "a: prod\n");
        Path file = fixture.root().resolve("config.yml");
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        GroupPrincipal initial = view.readAttributes().group();
        Process id = new ProcessBuilder("id", "-Gn").start();
        String groups = new String(id.getInputStream().readAllBytes()).strip();
        id.waitFor();
        UserPrincipalLookupService lookup = file.getFileSystem().getUserPrincipalLookupService();
        GroupPrincipal other = null;
        for (String name : groups.split("\\s+")) {
            if (!name.equals(initial.getName())) {
                other = lookup.lookupPrincipalByGroupName(name);
                break;
            }
        }
        assumeTrue(other != null, "needs a user in two groups");
        view.setGroup(other);

        assertEquals(List.of(), fixture.apply("files:\n  config.yml:\n    - {path: a, value: dev}\n").problems());
        assertEquals(other, Files.getFileAttributeView(file, PosixFileAttributeView.class).readAttributes().group());
    }

    private Path secretsFile(String content) throws IOException {
        Path secrets = temp.resolve("secrets.env");
        Files.writeString(secrets, content);
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"));
        return secrets;
    }
}
