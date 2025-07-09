package fr.mediavee.configinjector.processor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import fr.mediavee.configinjector.processor.AbstractFileProcessor.RequiredVariableValidator;

public interface FileProcessor {
    
    boolean canProcess(String fileName);
    
    boolean processFile(Path filePath, List<Map<String, Object>> changes) throws IOException;
    
    default boolean processFile(Path filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        return processFile(filePath, changes);
    }
    
    String getFormat();
}