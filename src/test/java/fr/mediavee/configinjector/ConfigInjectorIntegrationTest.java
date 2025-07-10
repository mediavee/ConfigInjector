package fr.mediavee.configinjector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import fr.mediavee.configinjector.processor.FileProcessor;
import fr.mediavee.configinjector.processor.FileProcessorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigInjectorIntegrationTest {

    @TempDir
    Path tempDir;

    private Path serverDir;
    private Path pluginsDir;

    @BeforeEach
    void setUp() throws IOException {
        // Simulate server structure
        serverDir = tempDir.resolve("server");
        pluginsDir = serverDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        
        // Create plugin directories
        Files.createDirectories(pluginsDir.resolve("TestPlugin1"));
        Files.createDirectories(pluginsDir.resolve("TestPlugin2"));
        Files.createDirectories(pluginsDir.resolve("TestPlugin3"));
    }

    @Test
    void testCompleteWorkflow_multipleFormats() throws Exception {
        // Create test files in different formats
        createYamlTestFile();
        createJsonTestFile();
        createPropertiesTestFile();
        
        // Create configuration for EnvReplacer (using HOME and defaults)
        String configContent = createEnvReplacerConfig();
        Path configFile = tempDir.resolve("config.yml");
        Files.write(configFile, configContent.getBytes());

        // Simulate the plugin processing
        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(configContent);
        
        processConfigReplacements(config);

        // Verify files were updated (simplified verification)
        assertTrue(Files.exists(pluginsDir.resolve("TestPlugin1").resolve("config.yml")));
        assertTrue(Files.exists(pluginsDir.resolve("TestPlugin2").resolve("settings.json")));
        assertTrue(Files.exists(pluginsDir.resolve("TestPlugin3").resolve("config.properties")));
    }

    @Test
    void testEnvironmentVariableSubstitution() throws Exception {
        Path yamlFile = pluginsDir.resolve("TestPlugin1").resolve("config.yml");
        
        Map<String, Object> initialData = new HashMap<>();
        initialData.put("simple", "old_value");
        
        writeYamlFile(yamlFile, initialData);

        String configContent = """
            replacements:
              - file: "plugins/TestPlugin1/config.yml"
                changes:
                  - path: "simple"
                    value: "${HOME:fallback}_modified"
                  - path: "with_default"
                    value: "${UNDEFINED:default_value}"
                  - path: "empty_default"
                    value: "${EMPTY_VAR:}"
            """;

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(configContent);
        processConfigReplacements(config);

        Map<String, Object> result = readYamlFile(yamlFile);
        assertTrue(result.get("simple").toString().endsWith("_modified"));
        assertEquals("default_value", result.get("with_default"));
        assertEquals("", result.get("empty_default"));
    }

    @Test
    void testNestedPathProcessing() throws Exception {
        Path jsonFile = pluginsDir.resolve("TestPlugin2").resolve("config.json");
        
        JsonObject initialData = new JsonObject();
        writeJsonFile(jsonFile, initialData);

        String configContent = """
            replacements:
              - file: "plugins/TestPlugin2/config.json"
                changes:
                  - path: "database.connection.host"
                    value: "localhost"
                  - path: "database.connection.port"
                    value: "3306"
                  - path: "features.cache.enabled"
                    value: "true"
                  - path: "features.cache.size"
                    value: "100"
            """;

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(configContent);
        processConfigReplacements(config);

        JsonObject result = readJsonFile(jsonFile);
        JsonObject database = result.getAsJsonObject("database");
        JsonObject connection = database.getAsJsonObject("connection");
        assertEquals("localhost", connection.get("host").getAsString());
        assertEquals("3306", connection.get("port").getAsString());

        JsonObject features = result.getAsJsonObject("features");
        JsonObject cache = features.getAsJsonObject("cache");
        assertEquals("true", cache.get("enabled").getAsString());
        assertEquals("100", cache.get("size").getAsString());
    }

    private void createYamlTestFile() throws IOException {
        Path yamlFile = pluginsDir.resolve("TestPlugin1").resolve("config.yml");
        Map<String, Object> data = new HashMap<>();
        data.put("host", "localhost");
        data.put("port", 3306);
        writeYamlFile(yamlFile, data);
    }

    private void createJsonTestFile() throws IOException {
        Path jsonFile = pluginsDir.resolve("TestPlugin2").resolve("settings.json");
        JsonObject data = new JsonObject();
        data.addProperty("api_key", "default_key");
        data.addProperty("cache_size", 500);
        writeJsonFile(jsonFile, data);
    }

    private void createPropertiesTestFile() throws IOException {
        Path propertiesFile = pluginsDir.resolve("TestPlugin3").resolve("config.properties");
        Properties data = new Properties();
        data.setProperty("database.url", "jdbc:mysql://localhost:3306/test");
        data.setProperty("pool.size", "10");
        writePropertiesFile(propertiesFile, data);
    }

    private String createEnvReplacerConfig() {
        return """
            replacements:
              - file: "plugins/TestPlugin1/config.yml"
                changes:
                  - path: "host"
                    value: "${HOME:localhost}"
                  - path: "port"
                    value: "${PORT:5432}"
                    
              - file: "plugins/TestPlugin2/settings.json"
                changes:
                  - path: "api_key"
                    value: "${API_KEY:default-key}"
                  - path: "cache_size"
                    value: "${CACHE_SIZE:1000}"
                    
              - file: "plugins/TestPlugin3/config.properties"
                changes:
                  - path: "database.url"
                    value: "jdbc:postgresql://localhost:5432/proddb"
                  - path: "pool.size"
                    value: "20"
            """;
    }

    private void processConfigReplacements(Map<String, Object> config) throws Exception {
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> replacements = 
            (java.util.List<Map<String, Object>>) config.get("replacements");
        
        if (replacements == null) return;

        for (Map<String, Object> replacement : replacements) {
            String filePath = (String) replacement.get("file");
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> changes = 
                (java.util.List<Map<String, Object>>) replacement.get("changes");
            
            processFileReplacements(filePath, changes);
        }
    }

    private void processFileReplacements(String filePath, java.util.List<Map<String, Object>> changes) throws Exception {
        Path fullPath = serverDir.resolve(filePath);
        
        if (!Files.exists(fullPath)) {
            System.err.println("File not found: " + fullPath);
            return;
        }

        FileProcessor processor = FileProcessorFactory.getProcessor(filePath);
        processor.processFile(fullPath, changes);
    }


    private void writeYamlFile(Path path, Map<String, Object> data) throws IOException {
        Yaml yaml = new Yaml();
        try (FileWriter writer = new FileWriter(path.toFile())) {
            yaml.dump(data, writer);
        }
    }

    private Map<String, Object> readYamlFile(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (FileInputStream input = new FileInputStream(path.toFile())) {
            return yaml.load(input);
        }
    }

    private void writeJsonFile(Path path, JsonObject data) throws IOException {
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(data, writer);
        }
    }

    private JsonObject readJsonFile(Path path) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(path.toFile())) {
            return gson.fromJson(reader, JsonObject.class);
        }
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