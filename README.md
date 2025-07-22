# ConfigInjector

Minecraft plugin that replaces environment variables in configuration files at startup.

## Features

- Replace environment variables into config files on server startup, before any other plugin enables
- Supports YAML, JSON, TOML and properties files
- Support for default values and nested properties
- Configurable error handling

## Configuration

```yaml
stop-on-missing-required: true  # Stop server if required variable is missing
stop-on-error: false # Continue on config errors (missing file, invalid format, etc.)

replacements:
  - file: "plugins/MyPlugin/config.yml"
    changes:
      - path: "database.host" 
        value: "${DB_HOST:localhost}" # Variable with default value
      - path: "database.password"
        value: "${DB_PASSWORD:}" # Variable with empty default
      - path: "api.key"
        value: "${API_KEY}" # Required variable, will stop server if missing (unless stop-on-missing-required is false)
      - path: "jdbc.url"
        value: "jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:mydb}?user=${DB_USER:root}&password=${DB_PASSWORD:}" # Complex example with multiple variables
```

## Compatibility

- Minecraft 1.8.9+
- Java 21+
- Bukkit, Spigot, Paper
