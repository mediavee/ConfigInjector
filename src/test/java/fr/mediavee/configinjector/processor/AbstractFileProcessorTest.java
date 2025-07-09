package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.processor.AbstractFileProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbstractFileProcessorTest {

    private TestFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TestFileProcessor();
    }

    @Test
    void testProcessEnvironmentVariables_withExistingEnvVar() {
        // Note: Since System.getenv is read-only, we test with PATH which should exist
        String result = processor.processEnvironmentVariables("${PATH:default}");
        assertNotNull(result);
        assertNotEquals("default", result); // PATH should be set on most systems
    }
    
    @Test
    void testProcessEnvironmentVariables_withoutColon() {
        // Test ${VAR} syntax without colon
        String result = processor.processEnvironmentVariables("${PATH}");
        assertNotNull(result);
        assertNotEquals("", result); // PATH should be set and not empty
    }

    @Test
    void testProcessEnvironmentVariables_withDefaultValue() {
        String result = processor.processEnvironmentVariables("${NON_EXISTENT_VAR:default_value}");
        assertEquals("default_value", result);
    }

    @Test
    void testProcessEnvironmentVariables_withoutDefaultValue() {
        String result = processor.processEnvironmentVariables("${NON_EXISTENT_VAR}");
        assertEquals("", result);
    }

    @Test
    void testProcessEnvironmentVariables_multipleVariables() {
        String result = processor.processEnvironmentVariables("${PATH:fallback}_${NON_EXISTENT:default}_suffix");
        assertTrue(result.endsWith("_default_suffix"));
        assertFalse(result.startsWith("fallback"));
    }

    @Test
    void testProcessEnvironmentVariables_nullValue() {
        String result = processor.processEnvironmentVariables(null);
        assertNull(result);
    }
    
    @Test
    void testProcessEnvironmentVariables_nonExistentVarWithoutDefault() {
        // Test ${VAR} syntax for non-existent variable (should return empty string)
        String result = processor.processEnvironmentVariables("${NON_EXISTENT_VARIABLE_12345}");
        assertEquals("", result);
    }


    private static class TestFileProcessor extends AbstractFileProcessor {
        @Override
        public boolean canProcess(String fileName) {
            return true;
        }

        @Override
        public boolean processFile(Path filePath, List<Map<String, Object>> changes) {
            return false;
        }

        @Override
        public String getFormat() {
            return "TEST";
        }

        public String processEnvironmentVariables(String value) {
            return super.processEnvironmentVariables(value);
        }
    }
}