package fr.mediavee.configinjector.resolver;

import fr.mediavee.configinjector.resolver.impl.CompositeVariableResolver;
import fr.mediavee.configinjector.resolver.impl.EnvironmentFileResolver;
import fr.mediavee.configinjector.resolver.impl.SystemVariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentVariableResolverTest {

    @TempDir
    Path tempDir;
    
    private Path envFile;
    
    @BeforeEach
    void setUp() throws IOException {
        envFile = tempDir.resolve(".env");
    }
    
    @Test
    void testEnvironmentFileResolverWithoutEnvFile() {
        // Test with non-existent .env file
        EnvironmentFileResolver resolver = new EnvironmentFileResolver(envFile);
        
        // Should return null if variable doesn't exist in env file
        String result = resolver.getVariable("NON_EXISTENT_VAR_12345");
        assertNull(result);
    }
    
    @Test
    void testEnvironmentFileResolverWithEnvFile() throws IOException {
        // Create .env file with test variables
        Files.writeString(envFile, 
            "DB_HOST=test-mysql.example.com\n" +
            "DB_PORT=3306\n" +
            "API_KEY=secret-test-key\n" +
            "EMPTY_VAR=\n" +
            "# This is a comment\n" +
            "QUOTED_VAR=\"quoted-value\"\n" +
            "SINGLE_QUOTED='single-quoted'\n"
        );
        
        EnvironmentFileResolver resolver = new EnvironmentFileResolver(envFile);
        
        // Test normal variables
        assertEquals("test-mysql.example.com", resolver.getVariable("DB_HOST"));
        assertEquals("3306", resolver.getVariable("DB_PORT"));
        assertEquals("secret-test-key", resolver.getVariable("API_KEY"));
        
        // Test empty variable
        assertEquals("", resolver.getVariable("EMPTY_VAR"));
        
        // Test quoted variables (quotes should be removed)
        assertEquals("quoted-value", resolver.getVariable("QUOTED_VAR"));
        assertEquals("single-quoted", resolver.getVariable("SINGLE_QUOTED"));
        
        // Test non-existent variable
        assertNull(resolver.getVariable("NON_EXISTENT"));
    }
    
    @Test
    void testCompositeResolverPriority() throws IOException {
        // Create .env file with PATH variable
        Files.writeString(envFile, "PATH=/from/env/file\n");
        
        CompositeVariableResolver resolver = new CompositeVariableResolver(
            new SystemVariableResolver(),
            new EnvironmentFileResolver(envFile)
        );
        
        // System PATH should take priority over .env file
        String systemPath = System.getenv("PATH");
        String resolvedPath = resolver.getVariable("PATH");
        
        assertNotNull(systemPath);
        assertEquals(systemPath, resolvedPath);
        assertNotEquals("/from/env/file", resolvedPath);
    }
    
    @Test
    void testEnvironmentFileResolverInvalidLines() throws IOException {
        // Create .env file with various invalid lines
        Files.writeString(envFile,
            "VALID_VAR=valid_value\n" +
            "INVALID_LINE_WITHOUT_EQUALS\n" +
            "=EMPTY_KEY\n" +
            "  # Comment with spaces\n" +
            "\n" +  // Empty line
            "ANOTHER_VALID=another_value\n"
        );
        
        EnvironmentFileResolver resolver = new EnvironmentFileResolver(envFile);
        
        // Valid variables should work
        assertEquals("valid_value", resolver.getVariable("VALID_VAR"));
        assertEquals("another_value", resolver.getVariable("ANOTHER_VALID"));
        
        // Invalid lines should be ignored
        assertNull(resolver.getVariable("INVALID_LINE_WITHOUT_EQUALS"));
        assertNull(resolver.getVariable(""));
    }
    
    @Test
    void testEnvironmentFileResolverGetEnvFileVariables() throws IOException {
        Files.writeString(envFile, 
            "VAR1=value1\n" +
            "VAR2=value2\n"
        );
        
        EnvironmentFileResolver resolver = new EnvironmentFileResolver(envFile);
        Map<String, String> envVars = resolver.getEnvFileVariables();
        
        assertEquals(2, envVars.size());
        assertEquals("value1", envVars.get("VAR1"));
        assertEquals("value2", envVars.get("VAR2"));
    }
    
    @Test
    void testSystemVariableResolver() {
        SystemVariableResolver resolver = new SystemVariableResolver();
        
        // Should only use system environment variables
        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            assertEquals(systemPath, resolver.getVariable("PATH"));
        }
        
        // Non-existent variables should return null
        assertNull(resolver.getVariable("NON_EXISTENT_VAR_12345"));
    }
}