package fr.mediavee.configinjector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.mediavee.configinjector.ConfigPath.Index;
import fr.mediavee.configinjector.ConfigPath.Key;
import fr.mediavee.configinjector.ConfigPath.Segment;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Writes edited values into the source text itself, so comments, order, quoting and indentation of
 * everything else stay as they were. YAML only, and JSON read as YAML.
 */
final class Splice {

    record Edit(ConfigPath path, Object value) {
    }

    private record Range(int start, int end, String text) {
    }

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private Splice() {
    }

    /**
     * The edited text, or empty when an edit cannot be written in place (a key to create, an anchored
     * value, a value that needs several lines) or the result would not read back as {@code expected}.
     */
    static Optional<String> apply(Format format, String text, List<Edit> edits, Object expected) {
        if (format != Format.YAML && format != Format.JSON) {
            return Optional.empty();
        }
        Node root;
        try {
            root = composer().compose(new StringReader(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<Range> ranges = new ArrayList<>();
        for (Edit edit : edits) {
            Optional<Range> range = find(root, edit.path().segments()).flatMap(node -> range(format, text, node, edit.value()));
            if (range.isEmpty()) {
                return Optional.empty();
            }
            ranges.add(range.get());
        }
        ranges.sort(Comparator.comparingInt(Range::start));
        StringBuilder out = new StringBuilder(text.length());
        int position = 0;
        for (Range range : ranges) {
            if (range.start() < position) {
                return Optional.empty();
            }
            out.append(text, position, range.start()).append(range.text());
            position = range.end();
        }
        String result = out.append(text, position, text.length()).toString();
        try {
            return Tree.sameValue(format.parse(result), expected) ? Optional.of(result) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Node> find(Node root, List<Segment> segments) {
        Optional<Node> node = Optional.of(root);
        for (Segment segment : segments) {
            node = node.flatMap(parent -> child(parent, segment));
        }
        return node;
    }

    private static Optional<Node> child(Node parent, Segment segment) {
        return switch (segment) {
            case Key(String name) when parent instanceof MappingNode mapping -> {
                List<Node> matches = mapping.getValue().stream()
                        .filter(tuple -> tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name))
                        .map(NodeTuple::getValueNode)
                        .toList();
                // A duplicated key is ambiguous in place; the rewrite keeps the parser's choice.
                yield matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
            }
            case Index(int position) when parent instanceof SequenceNode sequence && position < sequence.getValue().size() ->
                    Optional.of(sequence.getValue().get(position));
            default -> Optional.empty();
        };
    }

    private static Optional<Range> range(Format format, String text, Node node, Object value) {
        // Replacing an anchored node would leave its aliases pointing at nothing.
        if (node.getAnchor() != null) {
            return Optional.empty();
        }
        Optional<String> rendered = format == Format.JSON ? Optional.of(COMPACT.toJson(value)) : renderYaml(value, node);
        if (rendered.isEmpty() || rendered.get().contains("\n")) {
            return Optional.empty();
        }
        int start = text.offsetByCodePoints(0, node.getStartMark().getIndex());
        int end = text.offsetByCodePoints(0, end(node).getIndex());
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        String replacement = rendered.get();
        // A block collection starts on the line after its key: the flow value goes right after the colon.
        int before = start;
        while (before > 0 && Character.isWhitespace(text.charAt(before - 1))) {
            before--;
        }
        if (before < start && text.substring(before, start).contains("\n") && before > 0 && text.charAt(before - 1) == ':') {
            start = before;
            replacement = " " + replacement;
        }
        return Optional.of(new Range(start, end, replacement));
    }

    /** A block collection ends at the next token, past the comments after it: use its last item's end. */
    private static Mark end(Node node) {
        return switch (node) {
            case MappingNode mapping when mapping.getFlowStyle() == DumperOptions.FlowStyle.BLOCK && !mapping.getValue().isEmpty() ->
                    end(mapping.getValue().getLast().getValueNode());
            case SequenceNode sequence when sequence.getFlowStyle() == DumperOptions.FlowStyle.BLOCK && !sequence.getValue().isEmpty() ->
                    end(sequence.getValue().getLast());
            default -> node.getEndMark();
        };
    }

    /** One line of YAML; a string keeps the quoting of the value it replaces. */
    private static Optional<String> renderYaml(Object value, Node node) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setSplitLines(false);
        options.setWidth(Integer.MAX_VALUE);
        options.setAllowUnicode(true);
        if (value instanceof String && node instanceof ScalarNode scalar
                && (scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED
                || scalar.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED)) {
            options.setDefaultScalarStyle(scalar.getScalarStyle());
        }
        String dumped = new Yaml(options).dump(value).strip();
        return dumped.isEmpty() ? Optional.empty() : Optional.of(dumped);
    }

    private static Yaml composer() {
        LoaderOptions loader = new LoaderOptions();
        loader.setCodePointLimit(Integer.MAX_VALUE);
        loader.setWarnOnDuplicateKeys(false);
        return new Yaml(loader);
    }
}
