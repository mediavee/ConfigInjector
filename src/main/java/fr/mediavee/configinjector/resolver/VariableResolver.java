package fr.mediavee.configinjector.resolver;

/**
 * Interface for resolving environment variables from various sources.
 */
public interface VariableResolver {
    
    /**
     * Resolves the value of an environment variable.
     * 
     * @param varName the name of the variable to resolve
     * @return the value of the variable, or null if not found
     */
    String getVariable(String varName);
}