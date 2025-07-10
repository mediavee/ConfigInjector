package fr.mediavee.configinjector;

import fr.mediavee.configinjector.processor.FileProcessor;
import fr.mediavee.configinjector.processor.FileProcessorFactory;
import fr.mediavee.configinjector.processor.AbstractFileProcessor.RequiredVariableValidator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class ConfigInjector extends JavaPlugin {

    private final boolean stopOnMissingRequired;
    private final boolean stopOnError;

    public ConfigInjector() {
        super();

        saveDefaultConfig();

        this.stopOnMissingRequired = getConfig().getBoolean("stop-on-missing-required", true);
        this.stopOnError = getConfig().getBoolean("stop-on-error", false);

        try {
            processConfigReplacements();
        } catch (MissingRequiredVariableException e) {
            handleError("Missing required variables: " + e.getMissingVariables(), e, stopOnMissingRequired);
        } catch (FileNotFoundException e) {
            handleError("Configuration file not found", e, stopOnError);
        } catch (IllegalArgumentException e) {
            handleError("Invalid configuration format", e, stopOnError);
        } catch (IOException e) {
            handleError("I/O error during configuration processing", e, stopOnError);
        } catch (Exception e) {
            handleError("Unexpected error during configuration processing", e, stopOnError);
        }
    }

    private void handleError(String message, Exception e, boolean shouldStop) {
        getLogger().log(Level.SEVERE, message, e);
        if (shouldStop) {
            getLogger().severe("Stopping server due to: " + message);
            System.exit(1);
        }
    }

    private void processConfigReplacements() throws IOException {
        Map<String, Object> config = getConfig().getValues(false);
        List<Map<String, Object>> replacements = (List<Map<String, Object>>) config.get("replacements");
        
        if (replacements == null) {
            return;
        }

        boolean stopOnMissingRequired = getConfig().getBoolean("stop-on-missing-required", true);
        List<String> missingVariables = new ArrayList<>();
        RequiredVariableValidator validator = new RequiredVariableValidator(stopOnMissingRequired, missingVariables);

        int processedFiles = 0;
        int modifiedFiles = 0;

        for (Map<String, Object> replacement : replacements) {
            String filePath = (String) replacement.get("file");
            List<Map<String, Object>> changes = (List<Map<String, Object>>) replacement.get("changes");
            
            if (processFileReplacements(filePath, changes, validator)) {
                modifiedFiles++;
            }
            processedFiles++;
        }

        if (!missingVariables.isEmpty()) {
            throw new MissingRequiredVariableException(missingVariables);
        }

        getLogger().info(String.format("Processed %d files, modified %d files", processedFiles, modifiedFiles));
    }

    private boolean processFileReplacements(String filePath, List<Map<String, Object>> changes) throws IOException {
        return processFileReplacements(filePath, changes, null);
    }
    
    private boolean processFileReplacements(String filePath, List<Map<String, Object>> changes, RequiredVariableValidator validator) throws IOException {
        Path serverRoot = getServer().getWorldContainer().toPath();
        Path fullPath = serverRoot.resolve(filePath);
        
        if (!Files.exists(fullPath)) {
            throw new FileNotFoundException("Configuration file not found: " + fullPath);
        }

        FileProcessor processor = FileProcessorFactory.getProcessor(filePath);

        return processor.processFile(fullPath, changes, validator);
    }
}
