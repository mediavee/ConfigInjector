package fr.mediavee.configinjector.processor.impl;

import fr.mediavee.configinjector.resolver.impl.SystemVariableResolver;
import fr.mediavee.configinjector.resolver.VariableResolver;
import fr.mediavee.configinjector.processor.AbstractFileProcessor;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YamlFileProcessor extends AbstractFileProcessor {
    
    @Override
    public boolean canProcess(String fileName) {
        return fileName.toLowerCase().endsWith(".yml") || fileName.toLowerCase().endsWith(".yaml");
    }
    
    @Override
    public String getFormat() {
        return "YAML";
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException {
        return processFile(filePath, changes, null);
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        return processFile(filePath, changes, validator, new SystemVariableResolver());
    }
    
    @Override
    public boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator, VariableResolver resolver) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> data;
        
        if (Files.exists(filePath)) {
            try (InputStream input = Files.newInputStream(filePath)) {
                data = yaml.load(input);
            }
        } else {
            data = new HashMap<>();
        }
        
        if (data == null) {
            data = new HashMap<>();
        }
        
        boolean modified = false;
        for (Map<String, Object> change : changes) {
            String path = (String) change.get("path");
            String value = (String) change.get("value");
            
            String processedValue = processEnvironmentVariables(value, validator, resolver);
            
            if (setNestedValue(data, path, processedValue)) {
                modified = true;
            }
        }
        
        if (modified) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml outputYaml = new Yaml(options);
            
            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                outputYaml.dump(data, writer);
            }
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