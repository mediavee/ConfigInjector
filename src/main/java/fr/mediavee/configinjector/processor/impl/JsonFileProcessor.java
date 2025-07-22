package fr.mediavee.configinjector.processor.impl;

import com.google.gson.*;
import fr.mediavee.configinjector.processor.AbstractFileProcessor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class JsonFileProcessor extends AbstractFileProcessor {
    
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    @Override
    public boolean canProcess(String fileName) {
        return fileName.toLowerCase().endsWith(".json");
    }
    
    @Override
    public String getFormat() {
        return "JSON";
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException {
        return processFile(filePath, changes, null);
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        JsonObject data;
        
        if (Files.exists(filePath)) {
            try (Reader reader = Files.newBufferedReader(filePath)) {
                JsonParser parser = new JsonParser();
                data = parser.parse(reader).getAsJsonObject();
            }
        } else {
            data = new JsonObject();
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
            try (Writer writer = Files.newBufferedWriter(filePath)) {
                gson.toJson(data, writer);
            }
        }
        
        return modified;
    }
    
    private boolean setNestedValue(JsonObject data, String path, String value) {
        String[] keys = path.split("\\.");
        JsonObject current = data;
        
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            JsonElement element = current.get(key);
            
            if (element != null && element.isJsonObject()) {
                current = element.getAsJsonObject();
            } else {
                JsonObject newObject = new JsonObject();
                current.add(key, newObject);
                current = newObject;
            }
        }
        
        String finalKey = keys[keys.length - 1];
        JsonElement oldValue = current.get(finalKey);
        JsonElement newValue = new JsonPrimitive(value);
        
        current.add(finalKey, newValue);
        return !newValue.equals(oldValue);
    }
    
}