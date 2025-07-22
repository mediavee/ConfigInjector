package fr.mediavee.configinjector.processor.impl;

import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import fr.mediavee.configinjector.processor.AbstractFileProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TomlFileProcessor extends AbstractFileProcessor {
    
    @Override
    public boolean canProcess(String fileName) {
        return fileName.toLowerCase().endsWith(".toml");
    }
    
    @Override
    public String getFormat() {
        return "TOML";
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException {
        return processFile(filePath, changes, null);
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        Map<String, Object> data;
        
        if (Files.exists(filePath)) {
            Toml toml = new Toml().read(filePath.toFile());
            data = toml.toMap();
        } else {
            data = new HashMap<>();
        }
        
        boolean modified = false;
        for (Map<String, Object> change : changes) {
            String path = (String) change.get("path");
            String value = (String) change.get("value");
            
            String processedValue = processEnvironmentVariables(value, validator);
            
            if (setNestedValue(data, path, processedValue)) {
                modified = true;
            }
        }
        
        if (modified) {
            TomlWriter writer = new TomlWriter();
            writer.write(data, filePath.toFile());
        }
        
        return modified;
    }
    
    @SuppressWarnings("unchecked")
    private boolean setNestedValue(Map<String, Object> data, String path, String value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = data;
        
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object next = current.get(key);
            
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                Map<String, Object> newMap = new HashMap<>();
                current.put(key, newMap);
                current = newMap;
            }
        }
        
        String finalKey = keys[keys.length - 1];
        Object oldValue = current.get(finalKey);
        
        current.put(finalKey, value);
        return !value.equals(oldValue);
    }
}