package fr.mediavee.configinjector.processor;

import com.moandjiezana.toml.Toml;
import fr.mediavee.configinjector.processor.impl.TomlFileProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TomlFileProcessorTest {

    @TempDir
    Path tempDir;

    private TomlFileProcessor processor;
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        processor = new TomlFileProcessor();
        testFile = tempDir.resolve("test.toml");
    }

    @Test
    void testCanProcess() {
        assertTrue(processor.canProcess("config.toml"));
        assertTrue(processor.canProcess("CONFIG.TOML"));
        assertFalse(processor.canProcess("config.yml"));
        assertFalse(processor.canProcess("config.json"));
        assertFalse(processor.canProcess("config.properties"));
    }

    @Test
    void testGetFormat() {
        assertEquals("TOML", processor.getFormat());
    }

    @Test
    void testProcessFile_simpleValue() throws Exception {
        // Create initial TOML file
        String initialContent = """
            host = "localhost"
            port = 3306
            """;
        Files.write(testFile, initialContent.getBytes());

        // Create changes
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "newhost"),
            createChange("port", "5432")
        );

        // Process file
        boolean modified = processor.processFile(testFile, changes);

        // Verify
        assertTrue(modified);
        Toml result = new Toml().read(testFile.toFile());
        assertEquals("newhost", result.getString("host"));
        assertEquals("5432", result.getString("port"));
    }

    @Test
    void testProcessFile_nestedValues() throws Exception {
        // Create initial TOML file
        String initialContent = """
            [database]
            host = "localhost"
            port = 3306
            
            [cache]
            enabled = true
            """;
        Files.write(testFile, initialContent.getBytes());

        // Create changes
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "newhost"),
            createChange("database.timeout", "30"),
            createChange("cache.size", "1000")
        );

        // Process file
        boolean modified = processor.processFile(testFile, changes);

        // Verify
        assertTrue(modified);
        Toml result = new Toml().read(testFile.toFile());
        assertEquals("newhost", result.getString("database.host"));
        assertEquals("30", result.getString("database.timeout"));
        assertEquals("1000", result.getString("cache.size"));
    }

    @Test
    void testProcessFile_withEnvironmentVariables() throws Exception {
        // Set environment variable for test
        String homeValue = System.getenv("HOME");
        assertNotNull(homeValue, "HOME environment variable should be set for this test");

        // Create initial TOML file
        String initialContent = """
            path = "/default/path"
            """;
        Files.write(testFile, initialContent.getBytes());

        // Create changes with environment variables
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("path", "${HOME}/custom"),
            createChange("fallback", "${NONEXISTENT:default_value}")
        );

        // Process file
        boolean modified = processor.processFile(testFile, changes);

        // Verify
        assertTrue(modified);
        Toml result = new Toml().read(testFile.toFile());
        assertEquals(homeValue + "/custom", result.getString("path"));
        assertEquals("default_value", result.getString("fallback"));
    }

    @Test
    void testProcessFile_createNewFile() throws Exception {
        // Create changes for non-existent file
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("server.host", "localhost"),
            createChange("server.port", "8080")
        );

        // Process file
        boolean modified = processor.processFile(testFile, changes);

        // Verify
        assertTrue(modified);
        assertTrue(Files.exists(testFile));
        
        Toml result = new Toml().read(testFile.toFile());
        assertEquals("localhost", result.getString("server.host"));
        assertEquals("8080", result.getString("server.port"));
    }

    @Test
    void testProcessFile_allValueTypes() throws Exception {
        // Create initial TOML file
        String initialContent = """
            string_val = "text"
            """;
        Files.write(testFile, initialContent.getBytes());

        // Create changes with different types
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("string_val", "new_text"),
            createChange("int_val", "42"),
            createChange("float_val", "3.14"),
            createChange("bool_val", "true")
        );

        // Process file
        boolean modified = processor.processFile(testFile, changes);

        // Verify
        assertTrue(modified);
        Toml result = new Toml().read(testFile.toFile());
        assertEquals("new_text", result.getString("string_val"));
        assertEquals("42", result.getString("int_val"));
        assertEquals("3.14", result.getString("float_val"));
        assertEquals("true", result.getString("bool_val"));
    }

    private Map<String, Object> createChange(String path, String value) {
        Map<String, Object> change = new HashMap<>();
        change.put("path", path);
        change.put("value", value);
        return change;
    }
}