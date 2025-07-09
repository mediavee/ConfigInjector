package fr.mediavee.configinjector.processor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PropertiesFileProcessor extends AbstractFileProcessor {
    
    @Override
    public boolean canProcess(String fileName) {
        return fileName.toLowerCase().endsWith(".properties");
    }
    
    @Override
    public String getFormat() {
        return "Properties";
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException {
        return processFile(filePath, changes, null);
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        Properties properties = new Properties();
        
        if (Files.exists(filePath)) {
            try (InputStream input = Files.newInputStream(filePath)) {
                properties.load(input);
            }
        }
        
        boolean modified = false;
        for (Map<String, Object> change : changes) {
            String path = (String) change.get("path");
            String value = (String) change.get("value");
            
            String processedValue = processEnvironmentVariables(value, validator);
            String oldValue = properties.getProperty(path);
            
            if (!processedValue.equals(oldValue)) {
                properties.setProperty(path, processedValue);
                modified = true;
            }
        }
        
        if (modified) {
            try (OutputStream output = Files.newOutputStream(filePath)) {
                properties.store(output, null);
            }
        }
        
        return modified;
    }
}