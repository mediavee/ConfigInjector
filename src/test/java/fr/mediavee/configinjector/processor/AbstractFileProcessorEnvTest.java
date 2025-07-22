package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.resolver.impl.CompositeVariableResolver;
import fr.mediavee.configinjector.resolver.impl.EnvironmentFileResolver;
import fr.mediavee.configinjector.resolver.impl.SystemVariableResolver;
import fr.mediavee.configinjector.resolver.VariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbstractFileProcessorEnvTest {

    @TempDir
    Path tempDir;
    
    private TestFileProcessor processor;
    private Path envFile;
    
    @BeforeEach
    void setUp() throws IOException {
        processor = new TestFileProcessor();
        envFile = tempDir.resolve(".env");
    }
    
    @Test
    void testProcessEnvironmentVariablesWithEnvFile() throws IOException {
        // Create .env file
        Files.writeString(envFile, 
            "DB_HOST=env-mysql.example.com\n" +
            "DB_PORT=3306\n" +
            "API_KEY=env-secret-key\n"
        );
        
        VariableResolver resolver = new CompositeVariableResolver(
            new SystemVariableResolver(),
            new EnvironmentFileResolver(envFile)
        );
        
        // Test variable resolution with .env file
        String result = processor.processEnvironmentVariablesPublic(
            "Host: ${DB_HOST}, Port: ${DB_PORT}, Key: ${API_KEY}", 
            null, 
            resolver
        );
        
        assertEquals("Host: env-mysql.example.com, Port: 3306, Key: env-secret-key", result);
    }
    
    @Test
    void testSystemEnvironmentOverridesEnvFile() throws IOException {
        // Create .env file with PATH variable
        Files.writeString(envFile, "PATH=/from/env/file\n");
        
        VariableResolver resolver = new CompositeVariableResolver(
            new SystemVariableResolver(),
            new EnvironmentFileResolver(envFile)
        );
        
        // System PATH should override .env file
        String result = processor.processEnvironmentVariablesPublic("${PATH}", null, resolver);
        String systemPath = System.getenv("PATH");
        
        assertNotNull(systemPath);
        assertEquals(systemPath, result);
        assertNotEquals("/from/env/file", result);
    }
    
    @Test
    void testFallbackToDefaultWhenNotInEnvFile() throws IOException {
        // Create .env file without the variable we're testing
        Files.writeString(envFile, "OTHER_VAR=other_value\n");
        
        VariableResolver resolver = new CompositeVariableResolver(
            new SystemVariableResolver(),
            new EnvironmentFileResolver(envFile)
        );
        
        // Should fallback to default value
        String result = processor.processEnvironmentVariablesPublic(
            "${NON_EXISTENT_VAR:default_value}", 
            null, 
            resolver
        );
        
        assertEquals("default_value", result);
    }
    
    @Test
    void testBackwardCompatibilityWithSystemResolver() {
        // Test that system resolver works as fallback
        SystemVariableResolver systemResolver = new SystemVariableResolver();
        
        String result = processor.processEnvironmentVariablesPublic(
            "${NON_EXISTENT_VAR:default_value}", 
            null, 
            systemResolver
        );
        
        assertEquals("default_value", result);
        
        // Test with system environment variable
        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            String pathResult = processor.processEnvironmentVariablesPublic("${PATH}", null, systemResolver);
            assertEquals(systemPath, pathResult);
        }
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

        // Make the protected method public for testing
        public String processEnvironmentVariablesPublic(String value, RequiredVariableValidator validator, VariableResolver resolver) {
            return super.processEnvironmentVariables(value, validator, resolver);
        }
    }
}