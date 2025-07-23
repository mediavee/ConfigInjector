package fr.mediavee.configinjector.resolver.impl;

import fr.mediavee.configinjector.resolver.VariableResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Variable resolver that loads variables from a .env file.
 */
public class EnvironmentFileResolver implements VariableResolver {

    private static final Logger LOGGER = Logger.getLogger(EnvironmentFileResolver.class.getName());
    private final Map<String, String> envFileVariables;
    
    public EnvironmentFileResolver(Path envFilePath) {
        this.envFileVariables = loadEnvFile(envFilePath);
    }
    
    @Override
    public String getVariable(String varName) {
        return envFileVariables.get(varName);
    }

    public Map<String, String> getEnvFileVariables() {
        return new HashMap<>(envFileVariables);
    }
    
    private Map<String, String> loadEnvFile(Path envFilePath) {
        Map<String, String> variables = new HashMap<>();
        
        if (!Files.exists(envFilePath)) {
            LOGGER.info("Environment file not found at " + envFilePath);
            return variables;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(envFilePath)) {
            String line;
            int lineNumber = 0;
            
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Parse KEY=VALUE format
                int equalIndex = line.indexOf('=');
                if (equalIndex == -1) {
                    LOGGER.warning("Invalid line format in " + envFilePath + " at line " + lineNumber + ": " + line);
                    continue;
                }
                
                String key = line.substring(0, equalIndex).trim();
                String value = line.substring(equalIndex + 1).trim();
                
                if (key.isEmpty()) {
                    LOGGER.warning("Empty variable name in " + envFilePath + " at line " + lineNumber);
                    continue;
                }

                // Remove surrounding quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) || 
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                
                variables.put(key, value);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read environment file: " + envFilePath, e);
        }
        
        return variables;
    }
}