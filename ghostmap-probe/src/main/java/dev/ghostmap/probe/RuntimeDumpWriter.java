package dev.ghostmap.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/** Appends one complete aggregate runtime observation as each JSONL line. */
final class RuntimeDumpWriter {
    static final String SCHEMA = "ghostmap.runtime/v1";

    private final Path output;

    RuntimeDumpWriter(Path output) throws IOException {
        if (output == null) {
            throw new NullPointerException("output");
        }
        this.output = output.toAbsolutePath();
        Path parent = this.output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isDirectory(this.output)) {
            throw new IOException("runtime output is a directory: " + this.output);
        }
        Files.writeString(this.output, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    synchronized void append(ProbeOptions options, Instant startedAt, Instant endedAt, String reason,
                             BoundedEdgeStore store) throws IOException {
        StringBuilder json = new StringBuilder(Math.max(512, store.size() * 256));
        json.append('{');
        stringField(json, "schema", SCHEMA);
        comma(json);
        stringField(json, "runId", options.runId());
        comma(json);
        stringField(json, "startedAt", startedAt.toString());
        comma(json);
        stringField(json, "endedAt", endedAt.toString());
        comma(json);
        stringField(json, "reason", reason);
        comma(json);
        json.append("\"filter\":{");
        stringArrayField(json, "include", options.includes());
        comma(json);
        stringArrayField(json, "exclude", options.excludes());
        json.append('}');
        comma(json);
        numberField(json, "edgeLimit", store.edgeLimit());
        comma(json);
        numberField(json, "droppedEvents", store.droppedEvents());
        comma(json);
        json.append("\"edges\":[");
        List<BoundedEdgeStore.RecordedEdge> edges = store.snapshot();
        for (int index = 0; index < edges.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            edge(json, edges.get(index));
        }
        json.append("]}\n");
        Files.writeString(output, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void edge(StringBuilder json, BoundedEdgeStore.RecordedEdge edge) {
        json.append('{');
        json.append("\"caller\":");
        method(json, edge.key().caller());
        comma(json);
        json.append("\"callee\":");
        method(json, edge.key().callee());
        comma(json);
        numberField(json, "hits", edge.hits());
        comma(json);
        stringField(json, "dispatch", edge.dispatch().name());
        json.append('}');
    }

    private static void method(StringBuilder json, MethodIdentity method) {
        json.append('{');
        stringField(json, "owner", method.owner());
        comma(json);
        stringField(json, "name", method.name());
        comma(json);
        stringField(json, "descriptor", method.descriptor());
        json.append('}');
    }

    private static void stringArrayField(StringBuilder json, String name, List<String> values) {
        quote(json, name);
        json.append(":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            quote(json, values.get(index));
        }
        json.append(']');
    }

    private static void stringField(StringBuilder json, String name, String value) {
        quote(json, name);
        json.append(':');
        quote(json, value);
    }

    private static void numberField(StringBuilder json, String name, long value) {
        quote(json, name);
        json.append(':').append(value);
    }

    private static void comma(StringBuilder json) {
        json.append(',');
    }

    private static void quote(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
