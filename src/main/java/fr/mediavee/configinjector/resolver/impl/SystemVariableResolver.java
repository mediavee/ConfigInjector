package fr.mediavee.configinjector.resolver.impl;

import fr.mediavee.configinjector.resolver.VariableResolver;

/**
 * Variable resolver that uses system environment variables.
 */
public class SystemVariableResolver implements VariableResolver {
    
    @Override
    public String getVariable(String varName) {
        return System.getenv(varName);
    }
}