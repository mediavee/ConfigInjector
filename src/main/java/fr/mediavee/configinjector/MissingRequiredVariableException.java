package fr.mediavee.configinjector;

import java.util.ArrayList;
import java.util.List;

public class MissingRequiredVariableException extends RuntimeException {
    
    private final List<String> missingVariables;
    
    public MissingRequiredVariableException(List<String> missingVariables) {
        super("Missing required environment variables: " + String.join(", ", missingVariables));
        this.missingVariables = new ArrayList<>(missingVariables);
    }
    
    public List<String> getMissingVariables() {
        return missingVariables;
    }
}