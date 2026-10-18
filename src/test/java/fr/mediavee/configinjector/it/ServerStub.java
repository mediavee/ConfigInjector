package fr.mediavee.configinjector.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stands in for a server's main class in the agent tests. */
public final class ServerStub {

    private ServerStub() {
    }

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of("main-ran"), Files.readString(Path.of("config.yml")));
    }
}
