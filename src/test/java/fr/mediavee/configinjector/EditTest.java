package fr.mediavee.configinjector;

import fr.mediavee.configinjector.ConfigInjector.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditTest {

    @TempDir
    Path temp;

    private static final String TYPED_EDITS = """
            - {path: db.port, value: 30210}
            - {path: db.ssl, value: true}
            - {path: db.name, value: "30210"}
            - {path: db.hosts, value: [a, b]}
            """;

    @Test
    void yamlKeepsWrittenTypes() throws IOException {
        Map<?, ?> db = typedEdit("config.yml", "db:\n  port: 3306\n  ssl: false\n  name: prod\n  hosts: [x]\n");
        assertTypes(db);
    }

    @Test
    void jsonKeepsWrittenTypes() throws IOException {
        Map<?, ?> db = typedEdit("data.json", "{\"db\": {\"port\": 3306, \"ssl\": false, \"name\": \"prod\", \"hosts\": [\"x\"]}}");
        assertTypes(db);
    }

    @Test
    void tomlKeepsWrittenTypesAndComments() throws IOException {
        Map<?, ?> db = typedEdit("velocity.toml", "# top comment\n[db]\n# port comment\nport = 3306\nssl = false\nname = \"prod\"\nhosts = [\"x\"]\n");
        assertTypes(db);
        assertTrue(fixture.read("velocity.toml").contains("# port comment"));
    }

    @Test
    void hoconKeepsWrittenTypes() throws IOException {
        Map<?, ?> db = typedEdit("config.conf", "db {\n  port = 3306\n  ssl = false\n  name = prod\n  hosts = [x]\n}\n");
        assertTypes(db);
    }

    @Test
    void propertiesEditsOnlyTheTargetLines() throws IOException {
        fixture = OverlayFixture.in(temp).file("server.properties",
                "#Minecraft server properties\nserver-port=25565\nmotd=Prod \\u00e9\nquery.port=25565\nrcon.password=secret\n");
        Outcome outcome = fixture.apply("""
                files:
                  server.properties:
                    - {path: server-port, value: 30210}
                    - {path: query.port, value: 30211}
                    - {path: rcon.password, blank: true}
                    - {path: new-key, value: "a b", create: true}
                """);
        assertEquals(List.of(), outcome.problems());
        assertEquals("#Minecraft server properties\nserver-port=30210\nmotd=Prod \\u00e9\nquery.port=30211\nrcon.password=\nnew-key=a b\n",
                fixture.read("server.properties"));
    }

    @Test
    void missingPathIsAnErrorUnlessCreateIsSet() throws IOException {
        fixture = OverlayFixture.in(temp).file("config.yml", "sql:\n  address: prod\n");
        Outcome typo = fixture.apply("files:\n  config.yml:\n    - {path: sql.adress, value: dev}\n");
        assertEquals(List.of("config.yml: sql.adress: key 'adress' not found under 'sql' (set create: true to add it)"), typo.problems());
        assertEquals("sql:\n  address: prod\n", fixture.read("config.yml"));

        Outcome created = fixture.apply("files:\n  config.yml:\n    - {path: sql.pool.size, value: 4, create: true}\n");
        assertEquals(List.of(), created.problems());
        assertEquals("sql:\n  address: prod\n  pool:\n    size: 4\n", fixture.read("config.yml"));
    }

    @Test
    void neverReplacesAScalarToReachADeeperPath() throws IOException {
        fixture = OverlayFixture.in(temp).file("config.yml", "sql: prod\n");
        Outcome outcome = fixture.apply("files:\n  config.yml:\n    - {path: sql.address, value: dev, create: true}\n");
        assertEquals(List.of("config.yml: sql.address: 'sql' is a string, not a map"), outcome.problems());
    }

    @Test
    void listIndexesAndQuotedDottedKeys() throws IOException {
        fixture = OverlayFixture.in(temp).file("config.yml", """
                servers:
                  - {host: prod-a}
                  - {host: prod-b}
                keys:
                  dotted.key: {x: false}
                """);
        Outcome outcome = fixture.apply("""
                files:
                  config.yml:
                    - {path: "servers[1].host", value: dev-b}
                    - {path: "keys[\\"dotted.key\\"].x", value: true}
                """);
        assertEquals(List.of(), outcome.problems());
        Map<?, ?> tree = (Map<?, ?>) Format.YAML.parse(fixture.read("config.yml"));
        assertEquals("dev-b", ((Map<?, ?>) ((List<?>) tree.get("servers")).get(1)).get("host"));
        assertEquals(true, ((Map<?, ?>) ((Map<?, ?>) tree.get("keys")).get("dotted.key")).get("x"));

        Outcome outOfRange = fixture.apply("files:\n  config.yml:\n    - {path: \"servers[2].host\", value: x}\n");
        assertEquals(List.of("config.yml: servers[2].host: index [2] out of range under 'servers' (size 2)"), outOfRange.problems());
    }

    @Test
    void blankSetsAnEmptyString() throws IOException {
        fixture = OverlayFixture.in(temp).file("config.yml", "discord:\n  webhook: https://discord.com/api/webhooks/1\n");
        assertEquals(List.of(), fixture.apply("files:\n  config.yml:\n    - {path: discord.webhook, blank: true}\n").problems());
        assertEquals("discord:\n  webhook: ''\n", fixture.read("config.yml"));
    }

    @Test
    void rejectsUnknownOverlayKeys() throws IOException {
        fixture = OverlayFixture.in(temp).file("config.yml", "a: 1\n");
        Outcome outcome = fixture.apply("files:\n  config.yml:\n    - {path: a, valeu: 2}\n");
        assertTrue(outcome.problems().contains("config.yml edit #1: unknown key 'valeu' (expected blank, create, path, value)"),
                outcome.problems().toString());
    }

    @Test
    void jsonNumbersKeepTheirExactValue() throws IOException {
        fixture = OverlayFixture.in(temp).file("data.json", "{\"id\": 9223372036854775809, \"ratio\": 0.10000000000000000001, \"port\": 1}");
        assertEquals(List.of(), fixture.apply("files:\n  data.json:\n    - {path: port, value: 2}\n").problems());
        assertEquals("{\n  \"id\": 9223372036854775809,\n  \"ratio\": 0.10000000000000000001,\n  \"port\": 2\n}\n", fixture.read("data.json"));
    }

    @Test
    void propertyWithoutSeparatorGetsOne() throws IOException {
        fixture = OverlayFixture.in(temp).file("server.properties", "rcon.password\nmotd\n");
        assertEquals(List.of(), fixture.apply("files:\n  server.properties:\n    - {path: rcon.password, value: dev-secret}\n").problems());
        assertEquals("rcon.password=dev-secret\nmotd\n", fixture.read("server.properties"));
    }

    @Test
    void parseErrorsNeverQuoteTheFile() throws IOException {
        String secret = "TOP-SECRET-VALUE";
        Map<String, String> broken = Map.of(
                "a.yml", "password: " + secret + "\n  bad: [\n",
                "b.json", "{\"password\": \"" + secret + "\" garbage}",
                "c.toml", "password = \"" + secret + "\" garbage\n",
                "d.conf", "password = \"" + secret + "\" } garbage\n");
        fixture = OverlayFixture.in(temp);
        StringBuilder overlay = new StringBuilder("files:\n");
        for (Map.Entry<String, String> file : broken.entrySet()) {
            fixture.file(file.getKey(), file.getValue());
            overlay.append("  ").append(file.getKey()).append(":\n    - {path: password, value: dev}\n");
        }
        assertStderrHides(secret, overlay.toString(), 5);
        assertStderrHides(secret, "files: {config.yml: {raw: \"" + secret + "\"}\n", 2);
    }

    private void assertStderrHides(String secret, String overlay, int expectedLines) throws IOException {
        Files.writeString(fixture.overlay(), overlay);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ConfigInjector.cli(new String[]{"check", fixture.overlay().toString(), "--dir", fixture.root().toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(1, code);
        assertEquals(expectedLines, stderr.lines().count(), stderr);
        assertFalse(stderr.contains(secret), stderr);
    }

    private OverlayFixture fixture;

    private Map<?, ?> typedEdit(String file, String content) throws IOException {
        fixture = OverlayFixture.in(temp).file(file, content);
        Outcome outcome = fixture.apply("files:\n  " + file + ":\n" + TYPED_EDITS.indent(4));
        assertEquals(List.of(), outcome.problems());
        Format format = Format.byExtension(Path.of(file)).orElseThrow();
        Object tree = Tree.unwrap(format.parse(fixture.read(file)));
        return (Map<?, ?>) ((Map<?, ?>) tree).get("db");
    }

    private static void assertTypes(Map<?, ?> db) {
        assertInstanceOf(Number.class, db.get("port"));
        assertEquals(30210, ((Number) db.get("port")).intValue());
        assertEquals(true, db.get("ssl"));
        assertEquals("30210", db.get("name"));
        assertEquals(List.of("a", "b"), db.get("hosts"));
    }
}
