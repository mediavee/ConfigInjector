package fr.mediavee.configinjector.processor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fr.mediavee.configinjector.processor.impl.JsonFileProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileProcessorTest {

    private JsonFileProcessor processor;
    private Gson gson;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new JsonFileProcessor();
        gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Test
    void testCanProcess() {
        assertTrue(processor.canProcess("config.json"));
        assertTrue(processor.canProcess("settings.JSON"));
        assertFalse(processor.canProcess("config.yml"));
        assertFalse(processor.canProcess("config.properties"));
    }

    @Test
    void testGetFormat() {
        assertEquals("JSON", processor.getFormat());
    }

    @Test
    void testProcessFile_simpleValue() throws Exception {
        Path jsonFile = tempDir.resolve("test.json");
        
        JsonObject initialData = new JsonObject();
        initialData.addProperty("host", "localhost");
        initialData.addProperty("port", 3306);
        
        writeJsonFile(jsonFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "newhost"),
            createChange("port", "5432")
        );

        boolean result = processor.processFile(jsonFile, changes);
        assertTrue(result);

        JsonObject updatedData = readJsonFile(jsonFile);
        assertEquals("newhost", updatedData.get("host").getAsString());
        assertEquals("5432", updatedData.get("port").getAsString());
    }

    @Test
    void testProcessFile_nestedValue() throws Exception {
        Path jsonFile = tempDir.resolve("test.json");
        
        JsonObject database = new JsonObject();
        database.addProperty("host", "localhost");
        database.addProperty("port", 3306);
        
        JsonObject initialData = new JsonObject();
        initialData.add("database", database);
        
        writeJsonFile(jsonFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "remotehost"),
            createChange("database.username", "admin")
        );

        boolean result = processor.processFile(jsonFile, changes);
        assertTrue(result);

        JsonObject updatedData = readJsonFile(jsonFile);
        JsonObject updatedDatabase = updatedData.getAsJsonObject("database");
        assertEquals("remotehost", updatedDatabase.get("host").getAsString());
        assertEquals("3306", updatedDatabase.get("port").getAsString());
        assertEquals("admin", updatedDatabase.get("username").getAsString());
    }

    @Test
    void testProcessFile_withEnvironmentVariables() throws Exception {
        Path jsonFile = tempDir.resolve("test.json");
        
        JsonObject initialData = new JsonObject();
        initialData.addProperty("host", "localhost");
        
        writeJsonFile(jsonFile, initialData);

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("host", "${HOME:fallback}"),
            createChange("port", "${NONEXISTENT:8080}"),
            createChange("timeout", "${API_TIMEOUT:60}")
        );

        boolean result = processor.processFile(jsonFile, changes);
        assertTrue(result);

        JsonObject updatedData = readJsonFile(jsonFile);
        assertNotEquals("fallback", updatedData.get("host").getAsString());
        assertEquals("8080", updatedData.get("port").getAsString());
        assertEquals("60", updatedData.get("timeout").getAsString());
    }

    @Test
    void testProcessFile_createNewFile() throws Exception {
        Path jsonFile = tempDir.resolve("new.json");
        
        List<Map<String, Object>> changes = Arrays.asList(
            createChange("database.host", "localhost"),
            createChange("database.port", "3306")
        );

        boolean result = processor.processFile(jsonFile, changes);
        assertTrue(result);

        JsonObject data = readJsonFile(jsonFile);
        JsonObject database = data.getAsJsonObject("database");
        assertEquals("localhost", database.get("host").getAsString());
        assertEquals("3306", database.get("port").getAsString());
    }


    @Test
    void testProcessFile_allValueTypes() throws Exception {
        Path jsonFile = tempDir.resolve("test.json");
        writeJsonFile(jsonFile, new JsonObject());

        List<Map<String, Object>> changes = Arrays.asList(
            createChange("debug", "true"),
            createChange("port", "8080"),
            createChange("timeout", "3.14")
        );

        boolean result = processor.processFile(jsonFile, changes);
        assertTrue(result);

        JsonObject data = readJsonFile(jsonFile);
        assertEquals("true", data.get("debug").getAsString());
        assertEquals("8080", data.get("port").getAsString());
        assertEquals("3.14", data.get("timeout").getAsString());
    }


    private Map<String, Object> createChange(String path, String value) {
        Map<String, Object> change = new HashMap<>();
        change.put("path", path);
        change.put("value", value);
        return change;
    }

    private void writeJsonFile(Path path, JsonObject data) throws IOException {
        try (FileWriter writer = new FileWriter(path.toFile())) {
            gson.toJson(data, writer);
        }
    }

    private JsonObject readJsonFile(Path path) throws IOException {
        return gson.fromJson(Files.newBufferedReader(path), JsonObject.class);
    }
}