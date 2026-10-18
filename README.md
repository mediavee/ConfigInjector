# ConfigInjector

Turns a copy of a production Minecraft server (Paper, Velocity, ...) into a safe development
server. Before the server starts, it applies an **overlay** of development values (database
addresses, passwords, webhooks, ports) to the server's configuration files, then scans the files
for production values that must not survive. If anything is wrong, the server never starts.

## Why a Java agent

The overlay must be in place before the server reads any file: `server.properties`, `spigot.yml`,
`paper-global.yml` and `velocity.toml` are read long before any plugin loads, so a plugin is too
late. Server images are distroless (no shell), so a pre-start script is not an option either.

A Java agent's `premain` runs in the server's JVM before its `main`. ConfigInjector applies the
overlay there, and on any problem prints one line per problem to stderr and exits the JVM with
code 1: the server's `main` never runs.

## Usage

### As an agent (server start)

```
java -javaagent:/home/container/.dev/configinjector.jar=/home/container/.dev/overlay.yml -jar server.jar
```

File paths in the overlay are relative to the JVM working directory, which is the server root.

On Pterodactyl, add the flag before `-jar` in the server's startup command, for example:

```
java -Xms128M -XX:MaxRAMPercentage=95.0 -javaagent:/home/container/.dev/configinjector.jar=/home/container/.dev/overlay.yml -jar {{SERVER_JARFILE}}
```

Remove the 1.x ConfigInjector plugin from `plugins/` when switching: the agent replaces it.

### From the command line

```
java -jar configinjector.jar apply <overlay.yml> [--dir <server root>]
java -jar configinjector.jar check <overlay.yml> [--dir <server root>]
```

`apply` does what the agent does. `check` validates the overlay (every file, path and secret must
resolve) and runs the forbidden-values scan, without writing anything. `--dir` defaults to the
current directory.

### Exit codes

| Code | Meaning |
|------|---------|
| 0    | Overlay applied (or checked) and no forbidden value found |
| 1    | At least one problem; each is printed on its own line to stderr |
| 2    | Command-line usage error |

## Overlay format

```yaml
secrets: /home/container/.dev/secrets.env   # optional
files:
  plugins/LiteBans/config.yml:
    - {path: sql.address, value: "172.18.0.1:30210"}
    - {path: sql.password, value: "${secret:LITEBANS_DB_PASSWORD}"}
    - {path: discord.webhook, blank: true}
    - {path: some.port, value: 30210}
    - {path: "keys[\"dotted.key\"].x", value: true}
    - {path: new.section.key, value: 1, create: true}
  server.properties:
    - {path: server-port, value: 30210}
  plugins/x/settings.cfg:
    format: hocon
    set:
      - {path: storage.host, value: 172.18.0.1}
  forwarding.secret: {raw: "${secret:VELOCITY_FORWARDING_SECRET}"}
forbidden:
  sha256: [<hex>, ...]
  contains: ["hp110381-001.eu.clouddb.ovh.net", "141.94.103.40"]
  scan: ["**/*.yml", "**/*.yaml", "**/*.json", "**/*.toml", "**/*.properties", "**/*.conf", "**/*.hocon", "**/*.secret", "**/*.txt"]
  exclude: ["logs/**", "cache/**", "world*/**", "libraries/**", "versions/**"]
  max-file-size: 2MB
```

Unknown or repeated keys anywhere in the overlay are errors, so a typo never goes unnoticed.

### Files

Each entry under `files` is a path relative to the server root and one of:

- a list of edits;
- `{format: <format>, set: [edits]}` to override the format guessed from the extension;
- `{raw: <string>}` to replace the whole file, creating it if absent (new files get mode 600).

Entries naming the same file (`config.yml` and `./config.yml`) are merged into one. A target that
is, or sits under, a symlink is refused.

| Format       | Extensions        | Library                     |
|--------------|-------------------|-----------------------------|
| `yaml`       | `.yml`, `.yaml`   | SnakeYAML                   |
| `json`       | `.json`           | Gson                        |
| `toml`       | `.toml`           | night-config                |
| `hocon`      | `.conf`, `.hocon` | Typesafe Config             |
| `properties` | `.properties`     | none: edited line by line   |

What a rewrite keeps:

- YAML, JSON and HOCON files are re-serialized: comments are lost and HOCON keys are sorted.
  HOCON substitutions are resolved, as Configurate does when a plugin saves its config.
- TOML keeps its comments.
- `.properties` files keep every line except the edited ones, in place.
- JSON numbers keep their exact source text.

A file is rewritten only when an edit changes a value, so applying the same overlay twice leaves
the files untouched the second time.

### Edits

| Key      | Meaning |
|----------|---------|
| `path`   | Where to write. Keys are separated by `.`; `[n]` indexes a list; `["a.b"]` is a key containing dots. In `.properties` files the path is the property key as is (`query.port`). |
| `value`  | The value, with the YAML type it is written with: `30210` stays an integer, `"30210"` a string, `true` a boolean, `[a, b]` a list. |
| `blank`  | `blank: true` sets an empty string, instead of `value`. |
| `create` | `create: true` adds missing keys. Without it a missing key is an error. A list element is never created, and an existing value is never replaced by a map to reach a deeper path. |

### Placeholders

Strings in `value` and `raw` may contain:

- `${secret:NAME}`: looked up in the secrets file, then in the environment;
- `${env:NAME}`: looked up in the environment only.

An unresolved placeholder is an error. The secrets file holds `KEY=VALUE` lines (`#` starts a
comment) and must not be accessible by group or others (`chmod 600`). A relative `secrets` path
is resolved against the server root.

### Forbidden values

After applying, every file matching a `scan` glob and no `exclude` glob is checked. Globs are
relative to the server root: `*` matches within a directory, `**` across directories, `**/` zero
or more directories (`plugins/X/**/*.yml` matches `plugins/X/config.yml`), and `{a,b}` either
alternative. The overlay and the secrets file are never scanned.

The scan fails closed. Anything it cannot inspect is an error, not a skip: tell it explicitly with
`exclude`.

- A symlink, file or directory, inside the scanned tree is an error (the walk never follows links).
- A matching file that is not a regular file, or is larger than `max-file-size` (default 2MB), is
  an error.
- Every matching file is read, binary or not. A UTF-8 or UTF-16 byte order mark is honoured;
  anything else is decoded as UTF-8.

The rules:

- `contains`: a substring forbidden anywhere in a line, a value or a key.
- `sha256`: SHA-256 hashes (hex) of exact production values. The following are hashed, each
  exactly and without surrounding whitespace, trailing comma or quotes:
  - the whole file;
  - every line;
  - the value after each line's first `=` or `:`.

  Files in a supported format are also parsed, and every key and scalar value is checked. HOCON
  files are parsed without resolving substitutions. A file that fails to parse is still checked
  line by line.

A match is reported with its file, line, rule and key path, never with the value (a matching
key shows as `<forbidden key>` in paths):

```
configinjector: plugins/LiteBans/config.yml:12: value matches forbidden.sha256[0] (at sql.password)
```

To compute a hash: `printf '%s' 'the-prod-value' | sha256sum`.

If `sha256` and `contains` are both empty, no scan runs. Defaults for `scan` and `exclude` are the
values shown above.

### Errors

Every problem is collected before anything is written. A missing file, a missing path, an
unresolved placeholder, a parse error or a target that is not writable, in any file, means no file
is written at all.

Files are written atomically (temporary file in the same directory, then an atomic move), keeping
their permissions, and their owner and group where the process is allowed to set them. If a write
still fails midway, the files already written are restored from their original content.

Error messages never quote the content of a file or of the overlay: a parse error gives only the
format, line and column.

## Build

```
./gradlew build
```

The agent jar is `build/libs/configinjector-<version>.jar`. Deploy it as `configinjector.jar`
(for example `/home/container/.dev/configinjector.jar`) so the startup command does not change
between versions. It runs on Java 21 and later.
