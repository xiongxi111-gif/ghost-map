package dev.ghostmap.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Probe-owned diagnostics that never fall back to target stdout or stderr. */
final class ProbeLog {
    private final Path path;

    ProbeLog(Path path) {
        if (path == null) {
            throw new NullPointerException("path");
        }
        this.path = path;
    }

    synchronized void info(String message) {
        append("INFO", message, null);
    }

    synchronized void error(String message, Throwable failure) {
        append("ERROR", message, failure);
    }

    private void append(String level, String message, Throwable failure) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder line = new StringBuilder()
                    .append(Instant.now()).append(' ')
                    .append(level).append(' ')
                    .append(sanitize(message));
            if (failure != null) {
                line.append(" [").append(failure.getClass().getName());
                if (failure.getMessage() != null) {
                    line.append(": ").append(sanitize(failure.getMessage()));
                }
                line.append(']');
            }
            line.append(System.lineSeparator());
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (RuntimeException | IOException ignored) {
            // Logging must never affect the target process or use its output streams.
        }
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
