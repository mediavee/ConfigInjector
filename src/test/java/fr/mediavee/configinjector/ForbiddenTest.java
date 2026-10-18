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
    void skipsBinaryAndOversizedFiles() throws IOException {
        OverlayFixture fixture = OverlayFixture.in(temp)
                .file("big.txt", PROD_HOST + "\n" + "x".repeat(2048));
        Files.write(fixture.root().resolve("binary.txt"), ("\0" + PROD_HOST).getBytes(StandardCharsets.UTF_8));
        Outcome outcome = fixture.run(overlay() + "  max-file-size: 1KB\n", Mode.CHECK, Map.of());
        assertEquals(List.of(), outcome.problems());
        assertEquals(0, outcome.scanned());
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
