# ConfigInjector

A Minecraft plugin that injects environment variables into configuration files during server startup.

## Features

- **Environment variable injection** into configuration files at server startup
- **Multi-format support**: YAML, JSON, Properties, TOML
- **Flexible syntax**: Variables with or without default values
- **Configurable error handling**: Stop the server or continue according to preferences
- **Nested value modification**: Supports complex property paths

## Supported formats

| Format | Extensions | Example |
|--------|-----------|---------|
| YAML | `.yml`, `.yaml` | `database.host: localhost` |
| JSON | `.json` | `{"database": {"host": "localhost"}}` |
| Properties | `.properties` | `database.host=localhost` |
| TOML | `.toml` | `[database]`<br>`host = "localhost"` |

## Variable syntax

```yaml
# Variable without default - empty if not present
"${VAR_NAME}"

# Variable with default value
"${VAR_NAME:default_value}"

# Variable with empty default (no error even if required)
"${VAR_NAME:}"
```

## Configuration

```yaml
# Stop the server if a required variable is missing (default: true)
stop-on-missing-required: true

# Stop the server on configuration error (default: false)
stop-on-error: false

replacements:
  - file: "plugins/MyPlugin/config.yml"
    changes:
      - path: "database.host"
        value: "${DB_HOST:localhost}"
      - path: "database.port"
        value: "${DB_PORT:3306}"
      - path: "database.password"
        value: "${DB_PASSWORD:}"
      - path: "settings.debug"
        value: "${DEBUG_MODE:false}"

  - file: "plugins/MyPlugin/settings.json"
    changes:
      - path: "api.key"
        value: "${API_KEY:}"
      - path: "server.url"
        value: "${SERVER_URL:http://localhost:8080}"
```

## Usage examples

### Environment variables
```bash
export DB_HOST=mysql.example.com
export DB_PORT=3306
export API_KEY=my-secret-api-key
```

### YAML configuration
```yaml
# plugins/MyPlugin/config.yml
database:
  host: "${DB_HOST:localhost}"
  port: "${DB_PORT:3306}"
  password: "${DB_PASSWORD:}"
```

### JSON configuration
```json
{
  "api": {
    "key": "${API_KEY:}",
    "url": "${API_URL:http://localhost:8080}"
  }
}
```

### Properties configuration
```properties
# plugins/MyPlugin/config.properties
jdbc.url=${JDBC_URL:jdbc:mysql://localhost:3306/mydb}
jdbc.username=${JDBC_USER:root}
jdbc.password=${JDBC_PASSWORD:}
```

## Error handling

### Missing variables
- **`stop-on-missing-required: true`**: Stops the server if a variable without a default is missing
- **`stop-on-missing-required: false`**: Continues with an empty value

### Configuration errors
- **`stop-on-error: true`**: Stops the server on error (file not found, invalid format)
- **`stop-on-error: false`**: Continues despite errors

## Compatibility

- **Minecraft**: 1.8.9+
- **Servers**: Bukkit, Spigot, Paper
- **Java**: 8+
