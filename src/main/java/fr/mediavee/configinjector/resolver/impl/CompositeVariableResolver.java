package fr.mediavee.configinjector.resolver.impl;

import fr.mediavee.configinjector.resolver.VariableResolver;

import java.util.Arrays;
import java.util.List;

/**
 * Variable resolver that combines multiple resolvers with priority order.
 * Resolvers are checked in the order they are provided.
 */
public class CompositeVariableResolver implements VariableResolver {
    
    private final List<VariableResolver> resolvers;
    
    public CompositeVariableResolver(VariableResolver... resolvers) {
        this.resolvers = Arrays.asList(resolvers);
    }
    
    public CompositeVariableResolver(List<VariableResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }
    
    @Override
    public String getVariable(String varName) {
        for (VariableResolver resolver : resolvers) {
            String value = resolver.getVariable(varName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}