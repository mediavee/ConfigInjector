package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.processor.PropertiesFileProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesFileProcessorTest {

    private PropertiesFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new PropertiesFileProcessor();
    }

    @Test
    void testCanProcess() {
        assertTrue(processor.canProcess("config.properties"));
        assertTrue(processor.canProcess("application.PROPERTIES"));
        assertFalse(processor.canProcess("config.yml"));
        assertFalse(processor.canProcess("config.json"));
    }

    @Test
    void testGetFormat() {
        assertEquals("Properties", processor.getFormat());
    }

    @Test
    void testProcessFile_simpleValue() throws Exception {
        Path propertiesFile = tempDir.resolve("test.properties");
        
        Properties initialData = new Properties();
        initialData.setProperty("host", "localhost");
        initialData.setProperty("port", "3306");
        
        writePropertiesFile(propertiesFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "newhost"),
            createChange("port", "5432")
        );

        boolean result = processor.processFile(propertiesFile, changes);
        assertTrue(result);

        Properties updatedData = readPropertiesFile(propertiesFile);
        assertEquals("newhost", updatedData.getProperty("host"));
        assertEquals("5432", updatedData.getProperty("port"));
    }

    @Test
    void testProcessFile_nestedValue() throws Exception {
        Path propertiesFile = tempDir.resolve("test.properties");
        
        Properties initialData = new Properties();
        initialData.setProperty("database.host", "localhost");
        initialData.setProperty("database.port", "3306");
        
        writePropertiesFile(propertiesFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "remotehost"),
            createChange("database.username", "admin")
        );

        boolean result = processor.processFile(propertiesFile, changes);
        assertTrue(result);

        Properties updatedData = readPropertiesFile(propertiesFile);
        assertEquals("remotehost", updatedData.getProperty("database.host"));
        assertEquals("3306", updatedData.getProperty("database.port"));
        assertEquals("admin", updatedData.getProperty("database.username"));
    }

    @Test
    void testProcessFile_withEnvironmentVariables() throws Exception {
        Path propertiesFile = tempDir.resolve("test.properties");
        
        Properties initialData = new Properties();
        initialData.setProperty("host", "localhost");
        
        writePropertiesFile(propertiesFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "${HOME:fallback}"),
            createChange("port", "${NONEXISTENT:5432}"),
            createChange("timeout", "${DB_TIMEOUT:30}")
        );

        boolean result = processor.processFile(propertiesFile, changes);
        assertTrue(result);

        Properties updatedData = readPropertiesFile(propertiesFile);
        assertNotEquals("fallback", updatedData.getProperty("host"));
        assertEquals("5432", updatedData.getProperty("port"));
        assertEquals("30", updatedData.getProperty("timeout"));
    }

    @Test
    void testProcessFile_createNewFile() throws Exception {
        Path propertiesFile = tempDir.resolve("new.properties");
        
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "localhost"),
            createChange("database.port", "3306")
        );

        boolean result = processor.processFile(propertiesFile, changes);
        assertTrue(result);

        Properties data = readPropertiesFile(propertiesFile);
        assertEquals("localhost", data.getProperty("database.host"));
        assertEquals("3306", data.getProperty("database.port"));
    }

    @Test
    void testProcessFile_allValueTypes() throws Exception {
        Path propertiesFile = tempDir.resolve("test.properties");
        writePropertiesFile(propertiesFile, new Properties());

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("debug", "true"),
            createChange("port", "8080"),
            createChange("timeout", "3.14"),
            createChange("message", "Hello World!"),
            createChange("path", "/path/to/file")
        );

        boolean result = processor.processFile(propertiesFile, changes);
        assertTrue(result);

        Properties data = readPropertiesFile(propertiesFile);
        assertEquals("true", data.getProperty("debug"));
        assertEquals("8080", data.getProperty("port"));
        assertEquals("3.14", data.getProperty("timeout"));
        assertEquals("Hello World!", data.getProperty("message"));
        assertEquals("/path/to/file", data.getProperty("path"));
    }

    private Map<String, Object> createChange(String path, String value) {
        Map<String, Object> change = new HashMap<>();
        change.put("path", path);
        change.put("value", value);
        return change;
    }

    private void writePropertiesFile(Path path, Properties data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            data.store(output, "Test properties");
        }
    }

    private Properties readPropertiesFile(Path path) throws IOException {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            props.load(input);
        }
        return props;
    }
}