package fr.mediavee.configinjector.processor;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class FileProcessorFactoryTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void testGetProcessor_yamlFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("config.yml");
        
        assertNotNull(processor);
        assertInstanceOf(YamlFileProcessor.class, processor);
        assertEquals("YAML", processor.getFormat());
    }

    @Test
    void testGetProcessor_yamlFileUppercase() {
        FileProcessor processor = FileProcessorFactory.getProcessor("CONFIG.YAML");
        
        assertNotNull(processor);
        assertInstanceOf(YamlFileProcessor.class, processor);
        assertEquals("YAML", processor.getFormat());
    }

    @Test
    void testGetProcessor_jsonFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("settings.json");
        
        assertNotNull(processor);
        assertInstanceOf(JsonFileProcessor.class, processor);
        assertEquals("JSON", processor.getFormat());
    }

    @Test
    void testGetProcessor_propertiesFile() {
        FileProcessor processor = FileProcessorFactory.getProcessor("application.properties");
        
        assertNotNull(processor);
        assertInstanceOf(PropertiesFileProcessor.class, processor);
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
        assertInstanceOf(YamlFileProcessor.class, processor);
    }

    @Test
    void testGetProcessor_priorityOrder() {
        FileProcessor yamlProcessor = FileProcessorFactory.getProcessor("test.yml");
        FileProcessor jsonProcessor = FileProcessorFactory.getProcessor("test.json");
        FileProcessor propProcessor = FileProcessorFactory.getProcessor("test.properties");

        assertInstanceOf(YamlFileProcessor.class, yamlProcessor);
        assertInstanceOf(JsonFileProcessor.class, jsonProcessor);
        assertInstanceOf(PropertiesFileProcessor.class, propProcessor);
        
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