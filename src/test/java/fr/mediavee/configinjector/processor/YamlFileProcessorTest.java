package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.processor.impl.YamlFileProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class YamlFileProcessorTest {

    private YamlFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new YamlFileProcessor();
    }

    @Test
    void testCanProcess() {
        assertTrue(processor.canProcess("config.yml"));
        assertTrue(processor.canProcess("settings.yaml"));
        assertTrue(processor.canProcess("CONFIG.YML"));
        assertFalse(processor.canProcess("config.json"));
        assertFalse(processor.canProcess("config.properties"));
    }

    @Test
    void testGetFormat() {
        assertEquals("YAML", processor.getFormat());
    }

    @Test
    void testProcessFile_simpleValue() throws Exception {
        Path yamlFile = tempDir.resolve("test.yml");
        
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("host", "localhost");
        initialData.put("port", 3306);
        
        writeYamlFile(yamlFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "newhost"),
            createChange("port", "5432")
        );

        boolean result = processor.processFile(yamlFile, changes);
        assertTrue(result);

        Map<String, Object> updatedData = readYamlFile(yamlFile);
        assertEquals("newhost", updatedData.get("host"));
        assertEquals("5432", updatedData.get("port"));
    }

    @Test
    void testProcessFile_nestedValue() throws Exception {
        Path yamlFile = tempDir.resolve("test.yml");
        
        Map<String, Object> database = new HashMap<>();
        database.put("host", "localhost");
        database.put("port", 3306);
        
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("database", database);
        
        writeYamlFile(yamlFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "remotehost"),
            createChange("database.username", "admin")
        );

        boolean result = processor.processFile(yamlFile, changes);
        assertTrue(result);

        Map<String, Object> updatedData = readYamlFile(yamlFile);
        Map<String, Object> updatedDatabase = (Map<String, Object>) updatedData.get("database");
        assertEquals("remotehost", updatedDatabase.get("host"));
        assertEquals(3306, updatedDatabase.get("port"));
        assertEquals("admin", updatedDatabase.get("username"));
    }

    @Test
    void testProcessFile_withEnvironmentVariables() throws Exception {
        Path yamlFile = tempDir.resolve("test.yml");
        
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("host", "localhost");
        
        writeYamlFile(yamlFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "${HOME:fallback}"),
            createChange("port", "${NONEXISTENT:5432}"),
            createChange("timeout", "${DB_TIMEOUT:30}")
        );

        boolean result = processor.processFile(yamlFile, changes);
        assertTrue(result);

        Map<String, Object> updatedData = readYamlFile(yamlFile);
        assertNotEquals("fallback", updatedData.get("host")); // HOME should exist
        assertEquals("5432", updatedData.get("port"));
        assertEquals("30", updatedData.get("timeout"));
    }

    @Test
    void testProcessFile_createNewFile() throws Exception {
        Path yamlFile = tempDir.resolve("new.yml");
        
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "localhost"),
            createChange("database.port", "3306")
        );

        boolean result = processor.processFile(yamlFile, changes);
        assertTrue(result);

        Map<String, Object> data = readYamlFile(yamlFile);
        Map<String, Object> database = (Map<String, Object>) data.get("database");
        assertEquals("localhost", database.get("host"));
        assertEquals("3306", database.get("port"));
    }

    @Test
    void testProcessFile_allValueTypes() throws Exception {
        Path yamlFile = tempDir.resolve("test.yml");
        writeYamlFile(yamlFile, new HashMap<>());

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("debug", "true"),
            createChange("port", "8080"),
            createChange("timeout", "3.14")
        );

        boolean result = processor.processFile(yamlFile, changes);
        assertTrue(result);

        Map<String, Object> data = readYamlFile(yamlFile);
        assertEquals("true", data.get("debug"));
        assertEquals("8080", data.get("port"));
        assertEquals("3.14", data.get("timeout"));
    }

    private Map<String, Object> createChange(String path, String value) {
        Map<String, Object> change = new HashMap<>();
        change.put("path", path);
        change.put("value", value);
        return change;
    }

    private void writeYamlFile(Path path, Map<String, Object> data) throws IOException {
        Yaml yaml = new Yaml();
        try (FileWriter writer = new FileWriter(path.toFile())) {
            yaml.dump(data, writer);
        }
    }

    private Map<String, Object> readYamlFile(Path path) throws IOException {
        Yaml yaml = new Yaml();
        return yaml.load(Files.newInputStream(path));
    }
}