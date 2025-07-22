# ConfigInjector

Minecraft plugin that replaces environment variables in configuration files at startup.

## Features

- Replace environment variables into config files on server startup, before any other plugin enables
- Supports YAML, JSON, TOML and properties files
- Support for default values and nested properties
- Optional `.env` file support with priority fallback
- Configurable error handling

## Configuration

Variables use the syntax `${VAR_NAME}` or `${VAR_NAME:default_value}`.

**Priority order:**
1. System environment variables
2. `.env` file variables
3. Default values

```yaml
stop-on-missing-required: true  # Stop server if required variable is missing
stop-on-error: false # Continue on config errors (missing file, invalid format, etc.)
env-file: ".env" # Optional .env file path (default: ".env")

replacements:
  - file: "plugins/MyPlugin/config.yml"
    changes:
      - path: "database.host" 
        value: "${DB_HOST:localhost}"
      - path: "database.password"
        value: "${DB_PASSWORD:}"
      - path: "jdbc.url"
        value: "jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:mydb}"
```

## Compatibility

- Minecraft 1.8.9+
- Java 17+
- Bukkit, Spigot, Paper
