package fr.mediavee.configinjector;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a development overlay to a server's configuration files, then fails if production
 * values remain. Runs as a Java agent, before the server reads any file, or from the command line.
 */
public final class ConfigInjector {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    enum Mode { APPLY, CHECK }

    record Outcome(List<String> problems, int written, ForbiddenScanner.Result scan) {

        Outcome(List<String> problems) {
            this(problems, 0, NO_SCAN);
        }

        int scanned() {
            return scan.scanned();
        }
    }

    private static final ForbiddenScanner.Result NO_SCAN = new ForbiddenScanner.Result(0, Duration.ZERO, List.of());

    private static final String USAGE_TEXT = """
            usage: java -jar configinjector.jar apply <overlay.yml> [--dir <server root>]
                   java -jar configinjector.jar check <overlay.yml> [--dir <server root>]
                   java -javaagent:configinjector.jar=<overlay.yml> -jar server.jar""";

    private ConfigInjector() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (args == null || args.isBlank()) {
            System.err.println("configinjector: missing overlay path, use -javaagent:configinjector.jar=<overlay.yml>");
            System.exit(FAILED);
        }
        int code = runAndReport(Path.of(args.strip()), Path.of(""), Mode.APPLY, System.out, System.err, "server start aborted");
        if (code != OK) {
            // The server's main must never run on a half-configured or still-production setup.
            System.exit(code);
        }
    }

    public static void main(String[] args) {
        System.exit(cli(args, System.out, System.err));
    }

    static int cli(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2 && !(args.length == 4 && args[2].equals("--dir"))) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        Mode mode = switch (args[0]) {
            case "apply" -> Mode.APPLY;
            case "check" -> Mode.CHECK;
            default -> null;
        };
        if (mode == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        Path root = Path.of(args.length == 4 ? args[3] : "");
        return runAndReport(Path.of(args[1]), root, mode, out, err, mode == Mode.APPLY ? "apply failed" : "check failed");
    }

    private static int runAndReport(Path overlay, Path root, Mode mode, PrintStream out, PrintStream err, String failure) {
        Outcome outcome;
        try {
            outcome = run(overlay, root, mode, System.getenv());
        } catch (IOException | RuntimeException e) {
            outcome = new Outcome(List.of("unexpected error: " + e));
        }
        if (!outcome.scan().heaviest().isEmpty()) {
            out.println("configinjector: warning: scanning " + outcome.scanned() + " file(s) took "
                    + outcome.scan().elapsed().toMillis() + " ms; folders with the most scanned files,"
                    + " to add to forbidden.exclude if they hold no configuration:");
            outcome.scan().heaviest().forEach(folder -> out.println("configinjector:   " + folder));
        }
        if (outcome.problems().isEmpty()) {
            String written = mode == Mode.APPLY ? outcome.written() + " file(s) written, " : "";
            out.println("configinjector: overlay OK, " + written + outcome.scanned() + " file(s) scanned for forbidden values");
            return OK;
        }
        outcome.problems().forEach(problem -> err.println("configinjector: " + problem));
        err.println("configinjector: " + outcome.problems().size() + " problem(s), " + failure);
        return FAILED;
    }

    static Outcome run(Path overlayFile, Path root, Mode mode, Map<String, String> environment) throws IOException {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return new Outcome(List.of("server root " + root.toAbsolutePath() + " is not a directory"));
        }
        Path realRoot = root.toRealPath();
        Path overlayPath = overlayFile.toAbsolutePath().normalize();
        Overlay overlay = Overlay.load(overlayPath, realRoot, problems);
        Placeholders placeholders = Placeholders.load(overlay.secrets(), environment, problems);
        List<Applier.PlannedWrite> writes = Applier.plan(realRoot, overlay, placeholders, problems);
        if (!problems.isEmpty()) {
            return new Outcome(problems);
        }
        if (mode == Mode.APPLY) {
            Applier.write(writes, problems);
            if (!problems.isEmpty()) {
                return new Outcome(problems);
            }
        }

        // The overlay and the secrets file legitimately hold the forbidden substrings and dev secrets.
        Set<Path> ignored = new HashSet<>();
        ignored.add(overlayPath.toRealPath());
        if (overlay.secrets().isPresent()) {
            ignored.add(overlay.secrets().get().toRealPath());
        }
        ForbiddenScanner.Result scan = ForbiddenScanner.scan(realRoot, overlay.forbidden(), ignored, problems);
        return new Outcome(problems, mode == Mode.APPLY ? writes.size() : 0, scan);
    }
}
