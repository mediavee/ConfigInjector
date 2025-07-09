package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.processor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class FileProcessorFactoryTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void testGetProcessor_yamlFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("config.yml");
        
        assertNotNull(processor);
        assertTrue(processor instanceof YamlFileProcessor);
        assertEquals("YAML", processor.getFormat());
    }

    @Test
    void testGetProcessor_yamlFileUppercase() {
        FileProcessor processor = FileProcessorFactory.getProcessor("CONFIG.YAML");
        
        assertNotNull(processor);
        assertTrue(processor instanceof YamlFileProcessor);
        assertEquals("YAML", processor.getFormat());
    }

    @Test
    void testGetProcessor_jsonFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("settings.json");
        
        assertNotNull(processor);
        assertTrue(processor instanceof JsonFileProcessor);
        assertEquals("JSON", processor.getFormat());
    }

    @Test
    void testGetProcessor_propertiesFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("application.properties");
        
        assertNotNull(processor);
        assertTrue(processor instanceof PropertiesFileProcessor);
        assertEquals("Properties", processor.getFormat());
    }

    @Test
    void testGetProcessor_unsupportedFile() {
        assertThrows(UnsupportedOperationException.class, () -> {
            FileProcessorFactory.getProcessor("config.xml");
        });
    }

    @Test
    void testGetProcessor_fileWithoutExtension() {
        assertThrows(UnsupportedOperationException.class, () -> {
            FileProcessorFactory.getProcessor("config");
        });
    }

    @Test
    void testGetProcessor_emptyFileName() {
        assertThrows(UnsupportedOperationException.class, () -> {
            FileProcessorFactory.getProcessor("");
        });
    }

    @Test
    void testGetProcessor_nullFileName() {
        assertThrows(IllegalArgumentException.class, () -> {
            FileProcessorFactory.getProcessor(null);
        });
    }

    @Test
    void testGetProcessor_fileWithPath() {
        FileProcessor processor = FileProcessorFactory.getProcessor("plugins/MyPlugin/config.yml");
        
        assertNotNull(processor);
        assertTrue(processor instanceof YamlFileProcessor);
    }

    @Test
    void testGetProcessor_fileWithComplexPath() {
        FileProcessor processor = FileProcessorFactory.getProcessor("/home/user/server/plugins/MyPlugin/settings.json");
        
        assertNotNull(processor);
        assertTrue(processor instanceof JsonFileProcessor);
    }

    @Test
    void testGetProcessor_priorityOrder() {
        FileProcessor yamlProcessor = FileProcessorFactory.getProcessor("test.yml");
        FileProcessor jsonProcessor = FileProcessorFactory.getProcessor("test.json");
        FileProcessor propProcessor = FileProcessorFactory.getProcessor("test.properties");
        
        assertTrue(yamlProcessor instanceof YamlFileProcessor);
        assertTrue(jsonProcessor instanceof JsonFileProcessor);
        assertTrue(propProcessor instanceof PropertiesFileProcessor);
        
        assertNotSame(yamlProcessor, jsonProcessor);
        assertNotSame(yamlProcessor, propProcessor);
        assertNotSame(jsonProcessor, propProcessor);
    }

    @Test
    void testGetProcessor_caseInsensitiveExtensions() {
        assertNotNull(FileProcessorFactory.getProcessor("config.YML"));
        assertNotNull(FileProcessorFactory.getProcessor("config.Yaml"));
        assertNotNull(FileProcessorFactory.getProcessor("config.JSON"));
        assertNotNull(FileProcessorFactory.getProcessor("config.Json"));
        assertNotNull(FileProcessorFactory.getProcessor("config.PROPERTIES"));
        assertNotNull(FileProcessorFactory.getProcessor("config.Properties"));
    }
}