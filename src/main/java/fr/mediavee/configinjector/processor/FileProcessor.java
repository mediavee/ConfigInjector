package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.resolver.VariableResolver;
import fr.mediavee.configinjector.processor.AbstractFileProcessor.RequiredVariableValidator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface FileProcessor {
    
    boolean canProcess(String fileName);
    
    boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException;
    
    default boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        return processFile(filePath, changes);
    }
    
    default boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator, VariableResolver resolver) throws IOException {
        return processFile(filePath, changes, validator);
    }
    
    String getFormat();
}