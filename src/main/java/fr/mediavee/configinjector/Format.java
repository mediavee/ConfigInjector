package fr.mediavee.configinjector;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supported file formats. Each parses to a tree of maps, lists and scalars; properties files are
 * parsed for scanning only and edited line by line by {@link PropertiesFile}.
 */
enum Format {
    YAML, JSON, TOML, HOCON, PROPERTIES;

    private static final Pattern LINE_COLUMN = Pattern.compile("line (\\d+) column (\\d+)");

    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    static Optional<Format> byExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "yml", "yaml" -> Optional.of(YAML);
            case "json" -> Optional.of(JSON);
            case "toml" -> Optional.of(TOML);
            case "conf", "hocon" -> Optional.of(HOCON);
            case "properties" -> Optional.of(PROPERTIES);
            default -> Optional.empty();
        };
    }

    static Optional<Format> byName(String name) {
        for (Format format : values()) {
            if (format.name().equalsIgnoreCase(name)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    Object parse(String text) {
        return switch (this) {
            case YAML -> {
                Object tree = yaml().load(text);
                yield tree == null ? new LinkedHashMap<>() : tree;
            }
            case JSON -> GSON.fromJson(text, Object.class);
            case TOML -> {
                CommentedConfig config = TomlFormat.newConfig(LinkedHashMap::new);
                new TomlParser().parse(new StringReader(text), config, ParsingMode.REPLACE);
                yield new TomlDocument(fromToml(config), config);
            }
            case HOCON -> ConfigFactory.parseString(text, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
                    .resolve()
                    .root()
                    .unwrapped();
            case PROPERTIES -> {
                Properties properties = new Properties();
                try {
                    properties.load(new StringReader(text));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Map<String, Object> map = new LinkedHashMap<>();
                properties.stringPropertyNames().forEach(key -> map.put(key, properties.getProperty(key)));
                yield map;
            }
        };
    }

    /**
     * Parses for the forbidden scan. HOCON substitutions stay unresolved: the server may resolve
     * them from sources the scan cannot see, so every literal value is still checked.
     */
    Object parseForScan(String text) {
        if (this != HOCON) {
            return parse(text);
        }
        return scanTree(ConfigFactory.parseString(text, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
                .root());
    }

    private static Object scanTree(ConfigValue value) {
        try {
            return switch (value) {
                case ConfigObject object -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    object.forEach((key, child) -> map.put(key, scanTree(child)));
                    yield map;
                }
                case ConfigList list -> {
                    List<Object> converted = new ArrayList<>(list.size());
                    list.forEach(child -> converted.add(scanTree(child)));
                    yield converted;
                }
                default -> value.unwrapped();
            };
        } catch (ConfigException.NotResolved e) {
            // An unresolved substitution has no value; its source text is covered by the line scan.
            return null;
        }
    }

    /**
     * Describes a parse failure by location only: library messages quote the offending input,
     * which may be a production secret.
     */
    String describeError(RuntimeException e) {
        String location = switch (e) {
            case MarkedYAMLException yaml when yaml.getProblemMark() != null ->
                    " at line " + (yaml.getProblemMark().getLine() + 1) + ", column " + (yaml.getProblemMark().getColumn() + 1);
            case ConfigException config when config.origin() != null && config.origin().lineNumber() > 0 ->
                    " at line " + config.origin().lineNumber();
            default -> {
                Matcher matcher = LINE_COLUMN.matcher(String.valueOf(e.getMessage()));
                yield matcher.find() ? " at line " + matcher.group(1) + ", column " + matcher.group(2) : "";
            }
        };
        String reason = e instanceof DuplicateKeyException ? "duplicate key" : "invalid " + name();
        return reason + location;
    }

    String write(Object tree) {
        return switch (this) {
            case YAML -> yaml().dump(tree);
            case JSON -> GSON.toJson(tree) + "\n";
            case TOML -> {
                TomlDocument document = (TomlDocument) tree;
                CommentedConfig config = TomlFormat.newConfig(LinkedHashMap::new);
                document.values().forEach((key, value) -> config.set(List.of(key), toToml(config, value)));
                config.putAllComments(document.original());
                yield new TomlWriter().writeToString(config);
            }
            case HOCON -> ConfigValueFactory.fromAnyRef(tree)
                    .render(ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false));
            case PROPERTIES -> throw new UnsupportedOperationException("properties files are edited line by line");
        };
    }

    private static Yaml yaml() {
        LoaderOptions loader = new LoaderOptions();
        loader.setCodePointLimit(Integer.MAX_VALUE);
        DumperOptions dumper = new DumperOptions();
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setIndent(2);
        dumper.setIndicatorIndent(2);
        dumper.setIndentWithIndicator(true);
        dumper.setSplitLines(false);
        dumper.setAllowUnicode(true);
        return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }

    /**
     * A TOML document as a plain map, plus the parsed config whose comments are copied back on write.
     */
    record TomlDocument(Map<String, Object> values, CommentedConfig original) {
    }

    private static Map<String, Object> fromToml(UnmodifiableConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        config.entrySet().forEach(entry -> values.put(entry.getKey(), fromTomlValue(entry.getValue())));
        return values;
    }

    private static Object fromTomlValue(Object value) {
        return switch (value) {
            case UnmodifiableConfig config -> fromToml(config);
            case List<?> list -> {
                List<Object> converted = new ArrayList<>(list.size());
                list.forEach(element -> converted.add(fromTomlValue(element)));
                yield converted;
            }
            case null, default -> value;
        };
    }

    private static Object toToml(Config parent, Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                Config sub = parent.createSubConfig();
                map.forEach((key, child) -> sub.set(List.of(String.valueOf(key)), toToml(sub, child)));
                yield sub;
            }
            case List<?> list -> {
                List<Object> converted = new ArrayList<>(list.size());
                list.forEach(element -> converted.add(toToml(parent, element)));
                yield converted;
            }
            case null, default -> value;
        };
    }
}
