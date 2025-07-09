package fr.mediavee.configinjector.processor;

import java.util.Arrays;
import java.util.List;

public class FileProcessorFactory {
    
    private static final List<FileProcessor> PROCESSORS = Arrays.asList(
        new YamlFileProcessor(),
        new JsonFileProcessor(),
        new PropertiesFileProcessor(),
        new TomlFileProcessor()
    );
    
    public static FileProcessor getProcessor(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("File name cannot be null");
        }
        
        for (FileProcessor processor : PROCESSORS) {
            if (processor.canProcess(fileName)) {
                return processor;
            }
        }
        
        throw new UnsupportedOperationException("No suitable processor found for file: " + fileName);
    }
}