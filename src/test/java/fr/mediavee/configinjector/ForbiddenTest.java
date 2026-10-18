package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigInjector.Mode;
import fr.mediavee.configinjector.ConfigInjector.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForbiddenTest {

    private static final String PROD_PASSWORD = "Pr0d-P4ssw0rd";
    private static final String PROD_HOST = "hp110381-001.eu.clouddb.ovh.net";

    @TempDir
    Path temp;

    @Test
    void reportsFileLineAndRuleWithoutTheValue() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp)
                .file("plugins/LiteBans/config.yml", "sql:\n  address: " + PROD_HOST + ":3306\n  password: '" + PROD_PASSWORD + "'\n")
                .file("data.json", "{\n  \"token\": \"" + PROD_PASSWORD + "\",\n  \"ok\": 1\n}\n")
                .file("forwarding.secret", PROD_PASSWORD + "\n")
                .file("server.properties", "rcon.password=" + PROD_PASSWORD + "\n")
                .file("logs/latest.log.txt", PROD_PASSWORD + "\n")
                .file("clean.yml", "a: dev\n");
        Outcome outcome = fixture.run(overlay(), Mode.CHECK, Map.of());

        assertEquals(List.of(
                "data.json:2: value matches forbidden.sha256[1] (at token)",
                "forwarding.secret:1: value matches forbidden.sha256[1]",
                "plugins/LiteBans/config.yml:2: value matches forbidden.contains[0] (at sql.address)",
                "plugins/LiteBans/config.yml:3: value matches forbidden.sha256[1] (at sql.password)",
                "server.properties:1: value matches forbidden.sha256[1] (at rcon.password)"
        ), outcome.problems().stream().sorted().toList());
        outcome.problems().forEach(problem -> {
            assertFalse(problem.contains(PROD_PASSWORD), problem);
            assertFalse(problem.contains(PROD_HOST), problem);
        });
    }

    @Test
    void passesOnceTheOverlayReplacedProductionValues() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp)
                .file("config.yml", "sql:\n  address: " + PROD_HOST + "\n  password: " + PROD_PASSWORD + "\n");
        Outcome outcome = fixture.apply("""
                files:
                  config.yml:
                    - {path: sql.address, value: 172.18.0.1}
                    - {path: sql.password, value: dev}
                """ + overlay());
        assertEquals(List.of(), outcome.problems());
        assertEquals(1, outcome.scanned());
    }

    @Test
    void scansFilesWithNulBytesAndUtf16() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.properties", "#\0\npassword=" + PROD_PASSWORD + "\n");
        byte[] utf16 = ("\uFEFFsql:\n  host: " + PROD_HOST + "\n").getBytes(StandardCharsets.UTF_16LE);
        Files.write(fixture.root().resolve("utf16.yml"), utf16);
        Outcome outcome = fixture.run(overlay(), Mode.CHECK, Map.of());
        assertEquals(List.of(
                "config.properties:2: value matches forbidden.sha256[1] (at password)",
                "utf16.yml:2: value matches forbidden.contains[0] (at sql.host)"
        ), outcome.problems().stream().sorted().toList());
    }

    @Test
    void oversizedFilesAreErrorsAndTheLimitMustBePositive() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("big.txt", "x".repeat(2048));
        assertEquals(List.of("big.txt: larger than forbidden.max-file-size (2048 bytes), exclude it explicitly or raise the limit"),
                fixture.run(overlay() + "  max-file-size: 1KB\n", Mode.CHECK, Map.of()).problems());
        assertEquals(List.of("forbidden.max-file-size must be positive"),
                fixture.run(overlay() + "  max-file-size: -1\n", Mode.CHECK, Map.of()).problems());
    }

    @Test
    void defaultScanCoversHoconFiles() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("config.hocon", "password = \"" + PROD_PASSWORD + "\"\n");
        assertEquals(List.of("config.hocon:1: value matches forbidden.sha256[1] (at password)"),
                fixture.run(overlay(), Mode.CHECK, Map.of()).problems());
    }

    @Test
    void doubleStarMatchesZeroOrMoreDirectories() throws IOException {
        ForbiddenScanner.Glob glob = ForbiddenScanner.Glob.of("plugins/X/**/*.yml");
        assertTrue(glob.matches(Path.of("plugins/X/config.yml")));
        assertTrue(glob.matches(Path.of("plugins/X/a/b/config.yml")));
        assertFalse(glob.matches(Path.of("plugins/Y/config.yml")));
        assertTrue(ForbiddenScanner.Glob.of("**/*.{yml,yaml}").matches(Path.of("a.yaml")));

        OverlayFixture fixture = OverlayFixture.in(temp).file("plugins/X/config.yml", "host: " + PROD_HOST + "\n");
        Outcome outcome = fixture.run(overlay() + "  scan: [\"plugins/X/**/*.yml\"]\n", Mode.CHECK, Map.of());
        assertEquals(List.of("plugins/X/config.yml:1: value matches forbidden.contains[0] (at host)"), outcome.problems());
    }

    @Test
    void hoconWithUnresolvedSubstitutionsIsStillChecked() throws IOException {
        String escaped = PROD_PASSWORD.replace("P", "\\u0050");
        OverlayFixture fixture = OverlayFixture.in(temp).file("plugin.conf", "password = \"" + escaped + "\"\nport = ${server.port}\n");
        assertEquals(List.of("plugin.conf: value matches forbidden.sha256[1] (at password)"),
                fixture.run(overlay(), Mode.CHECK, Map.of()).problems());
    }

    @Test
    void hashesTheExactTextAsWellAsItsTrimmedForms() throws IOException {
        String withComma = "prod-secret,";
        String quoted = "\"quoted-secret\"";
        OverlayFixture fixture = OverlayFixture.in(temp)
                .file("forwarding.secret", withComma)
                .file("other.txt", "key: " + quoted + "\n");
        Outcome outcome = fixture.run("forbidden:\n  sha256: [%s, %s]\n".formatted(sha256(withComma), sha256(quoted)),
                Mode.CHECK, Map.of());
        assertEquals(List.of("forwarding.secret:1: value matches forbidden.sha256[0]", "other.txt:1: value matches forbidden.sha256[1]"),
                outcome.problems().stream().sorted().toList());
    }

    @Test
    void mappingKeysAreCheckedToo() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp).file("keys.yml", "apiKeys:\n  " + PROD_PASSWORD + ": admin\n");
        fixture.file("nested.yml", "apiKeys:\n  " + PROD_PASSWORD + ":\n    role: " + PROD_HOST + "\n");
        assertEquals(List.of(
                "keys.yml:2: value matches forbidden.sha256[1] (a key under apiKeys)",
                "nested.yml:2: value matches forbidden.sha256[1] (a key under apiKeys)",
                "nested.yml:3: value matches forbidden.contains[0] (at apiKeys.<forbidden key>.role)"
        ), fixture.run(overlay(), Mode.CHECK, Map.of()).problems().stream().sorted().toList());
    }

    @Test
    void symlinksInTheScannedTreeAreErrors() throws IOException {
        Path outside = Files.writeString(temp.resolve("prod.yml"), "host: " + PROD_HOST + "\n");
        Path outsideDir = Files.createDirectories(temp.resolve("shared"));
        OverlayFixture fixture = OverlayFixture.in(temp);
        Files.createDirectories(fixture.root().resolve("plugins/X"));
        Files.createSymbolicLink(fixture.root().resolve("plugins/X/config.yml"), outside);
        Files.createSymbolicLink(fixture.root().resolve("plugins/linked"), outsideDir);
        Files.createSymbolicLink(fixture.root().resolve("logs"), outsideDir);
        assertEquals(List.of(
                "plugins/X/config.yml: symlink not allowed in the scanned tree, exclude it explicitly",
                "plugins/linked: symlink not allowed in the scanned tree, exclude it explicitly"
        ), fixture.run(overlay(), Mode.CHECK, Map.of()).problems().stream().sorted().toList());
    }

    private static String overlay() {
        return """
                forbidden:
                  sha256: [%s, %s]
                  contains: ["%s"]
                  exclude: ["logs/**"]
                """.formatted("a".repeat(64), sha256(PROD_PASSWORD), PROD_HOST);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
