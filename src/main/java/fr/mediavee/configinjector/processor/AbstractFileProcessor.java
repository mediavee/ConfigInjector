package fr.mediavee.configinjector.processor;

import fr.mediavee.configinjector.resolver.impl.SystemVariableResolver;
import fr.mediavee.configinjector.resolver.VariableResolver;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractFileProcessor implements FileProcessor {
    
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
    
    protected String processEnvironmentVariables(String value) {
        return processEnvironmentVariables(value, null, new SystemVariableResolver());
    }
    
    protected String processEnvironmentVariables(String value, RequiredVariableValidator validator) {
        return processEnvironmentVariables(value, validator, new SystemVariableResolver());
    }
    
    protected String processEnvironmentVariables(String value, RequiredVariableValidator validator, VariableResolver resolver) {
        if (value == null) return null;
        
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultValue = matcher.group(2);
            
            String envValue = resolver.getVariable(envVar);
            
            if (envValue == null) {
                if (validator != null) {
                    validator.checkRequired(envVar, defaultValue);
                }
                envValue = defaultValue != null ? defaultValue : "";
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    public static class RequiredVariableValidator {
        private final boolean stopOnMissingRequired;
        private final List<String> missingVariables;
        
        public RequiredVariableValidator(boolean stopOnMissingRequired, List<String> missingVariables) {
            this.stopOnMissingRequired = stopOnMissingRequired;
            this.missingVariables = missingVariables;
        }
        
        public void checkRequired(String varName, String defaultValue) {
            if (stopOnMissingRequired && defaultValue == null) {
                missingVariables.add(varName);
            }
        }
    }
    
}