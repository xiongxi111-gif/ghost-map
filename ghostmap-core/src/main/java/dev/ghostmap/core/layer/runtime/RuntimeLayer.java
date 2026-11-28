package dev.ghostmap.core.layer.runtime;

import dev.ghostmap.core.layer.Diagnostic;
import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.DiagnosticSeverity;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.Layer;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.RuntimeRun;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** L4 reader for complete aggregate records in {@code ghostmap.runtime/v1} JSONL. */
public final class RuntimeLayer implements Layer {
    public static final String SCHEMA = "ghostmap.runtime/v1";

    private final JsonMapper json = JsonMapper.shared();

    @Override
    public LayerId id() {
        return LayerId.RUNTIME;
    }

    @Override
    public LayerFacts analyse(LayerInput input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<RuntimeRecord> records = new ArrayList<>();
        input.runtimeFiles().stream().sorted().forEach(path -> read(path, records, diagnostics));

        Map<RecordKey, RuntimeRecord> latest = new LinkedHashMap<>();
        records.stream().sorted(Comparator.comparing(RuntimeRecord::endedAt)).forEach(record ->
                latest.put(new RecordKey(record.source(), record.runId()), record));

        Map<RuntimeFactKey, RuntimeCallFact> facts = new LinkedHashMap<>();
        for (RuntimeRecord record : latest.values()) {
            for (RuntimeEdge edge : record.edges()) {
                RuntimeFactKey key = new RuntimeFactKey(record.runId(), edge.caller(), edge.callee());
                RuntimeCallFact fact = new RuntimeCallFact(canonical(edge.caller()), canonical(edge.callee()),
                        record.runId(), edge.hits(), edge.dispatch(), record.startedAt(), record.endedAt(),
                        record.filter(), record.source());
                RuntimeCallFact existing = facts.get(key);
                if (existing == null) {
                    facts.put(key, fact);
                } else {
                    try {
                        facts.put(key, new RuntimeCallFact(existing.from(), existing.target(), existing.runId(),
                                Math.addExact(existing.hits(), fact.hits()), merge(existing.dispatch(), fact.dispatch()),
                                earliest(existing.startedAt(), fact.startedAt()), latest(existing.endedAt(), fact.endedAt()),
                                existing.filter(), existing.source()));
                    } catch (ArithmeticException exception) {
                        diagnostics.add(diagnostic(DiagnosticCode.RUNTIME_JSON_INVALID, record.source(), 0,
                                "runtime hit count overflow for " + edge.caller().value() + " -> "
                                        + edge.callee().value()));
                    }
                }
            }
        }

        List<RuntimeCallFact> sortedFacts = facts.values().stream().sorted(Comparator
                .comparing((RuntimeCallFact fact) -> fact.caller().value())
                .thenComparing(fact -> fact.callee().value())
                .thenComparing(RuntimeCallFact::runId)).toList();
        Map<String, FactNode> nodes = new LinkedHashMap<>();
        for (RuntimeCallFact fact : sortedFacts) {
            addNode(nodes, fact.caller(), fact.runId());
            addNode(nodes, fact.callee(), fact.runId());
        }
        List<EdgeFact> edgeFacts = sortedFacts.stream().map(fact -> (EdgeFact) fact).toList();
        return new LayerFacts(LayerId.RUNTIME, List.copyOf(nodes.values()), edgeFacts,
                diagnostics.stream().sorted(Comparator.comparing(Diagnostic::file)
                        .thenComparingInt(Diagnostic::line).thenComparing(Diagnostic::message)).toList());
    }

    /** Snapshot run metadata derivable from runtime facts without mutable layer state. */
    public static List<RuntimeRun> runtimeRuns(LayerFacts facts) {
        if (facts == null) {
            throw new NullPointerException("facts");
        }
        Map<String, RuntimeRun> runs = new LinkedHashMap<>();
        facts.edges().stream().filter(RuntimeCallFact.class::isInstance).map(RuntimeCallFact.class::cast)
                .sorted(Comparator.comparing(RuntimeCallFact::runId)).forEach(fact -> runs.putIfAbsent(fact.runId(),
                        new RuntimeRun(fact.runId(), fact.source().getFileName().toString(), fact.filter())));
        return List.copyOf(runs.values());
    }

    private void read(Path path, List<RuntimeRecord> records, List<Diagnostic> diagnostics) {
        if (!Files.isRegularFile(path)) {
            diagnostics.add(diagnostic(DiagnosticCode.RUNTIME_INPUT_NOT_FOUND, path, 0,
                    "runtime input is not a regular file"));
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ProbeRecord parsed = json.readValue(line, ProbeRecord.class);
                    if (parsed == null || !SCHEMA.equals(parsed.schema())) {
                        String found = parsed == null ? "null" : String.valueOf(parsed.schema());
                        diagnostics.add(diagnostic(DiagnosticCode.RUNTIME_SCHEMA_UNSUPPORTED, path, lineNumber,
                                "expected " + SCHEMA + " but found " + found));
                        continue;
                    }
                    records.add(validate(parsed, path));
                } catch (RuntimeException exception) {
                    diagnostics.add(diagnostic(DiagnosticCode.RUNTIME_JSON_INVALID, path, lineNumber,
                            message(exception)));
                }
            }
        } catch (IOException exception) {
            diagnostics.add(diagnostic(DiagnosticCode.RUNTIME_INPUT_READ_ERROR, path, 0, message(exception)));
        }
    }

    private static RuntimeRecord validate(ProbeRecord record, Path source) {
        requireText(record.runId(), "runId");
        requireText(record.startedAt(), "startedAt");
        requireText(record.endedAt(), "endedAt");
        requireText(record.reason(), "reason");
        if (record.filter() == null || record.filter().include() == null || record.filter().exclude() == null) {
            throw new IllegalArgumentException("filter.include and filter.exclude are required");
        }
        List<String> filter = strings(record.filter().include(), "filter.include");
        strings(record.filter().exclude(), "filter.exclude");
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("filter.include must not be empty");
        }
        if (record.edgeLimit() == null || record.edgeLimit() < 1) {
            throw new IllegalArgumentException("edgeLimit must be positive");
        }
        if (record.droppedEvents() == null || record.droppedEvents() < 0) {
            throw new IllegalArgumentException("droppedEvents must not be negative");
        }
        if (record.edges() == null) {
            throw new IllegalArgumentException("edges is required");
        }
        Instant startedAt = Instant.parse(record.startedAt());
        Instant endedAt = Instant.parse(record.endedAt());
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not precede startedAt");
        }
        List<RuntimeEdge> edges = new ArrayList<>();
        for (ProbeEdge edge : record.edges()) {
            if (edge == null || edge.caller() == null || edge.callee() == null) {
                throw new IllegalArgumentException("edge caller and callee are required");
            }
            if (edge.hits() == null || edge.hits() < 1) {
                throw new IllegalArgumentException("edge hits must be positive");
            }
            MethodId caller = method(edge.caller(), "caller");
            MethodId callee = method(edge.callee(), "callee");
            DispatchKind dispatch;
            try {
                dispatch = DispatchKind.valueOf(requireText(edge.dispatch(), "dispatch"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown runtime dispatch: " + edge.dispatch(), exception);
            }
            edges.add(new RuntimeEdge(caller, callee, edge.hits(), dispatch));
        }
        return new RuntimeRecord(record.runId(), startedAt, endedAt, filter, source,
                edges.stream().sorted(Comparator.comparing((RuntimeEdge edge) -> edge.caller().value())
                        .thenComparing(edge -> edge.callee().value())).toList());
    }

    private static MethodId method(ProbeMethod method, String field) {
        return new MethodId(requireText(method.owner(), field + ".owner"),
                requireText(method.name(), field + ".name"),
                requireText(method.descriptor(), field + ".descriptor"));
    }

    private static List<String> strings(List<String> values, String field) {
        List<String> copy = List.copyOf(values);
        copy.forEach(value -> requireText(value, field));
        return copy;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void addNode(Map<String, FactNode> nodes, MethodId method, String runId) {
        Map<String, JsonValue> attrs = Map.of(
                "abstract", new BooleanValue(false),
                "annotations", new ArrayValue(List.of()),
                "descriptor", new StringValue(method.descriptor()),
                "generated", new BooleanValue(false),
                "provenance", new ArrayValue(List.of(new StringValue(LayerId.RUNTIME.name()))),
                "runtimeRuns", new ArrayValue(List.of(new StringValue(runId))));
        nodes.putIfAbsent(method.value(), new FactNode(canonical(method), NodeKind.METHOD, method.name(),
                Optional.of(new TypeId(method.binaryOwner())), attrs, Optional.empty()));
    }

    private static SymbolId.Canonical canonical(MethodId method) {
        return new SymbolId.Canonical(method);
    }

    private static DispatchKind merge(DispatchKind left, DispatchKind right) {
        if (left == right || right == DispatchKind.UNKNOWN) {
            return left;
        }
        return left == DispatchKind.UNKNOWN ? right : DispatchKind.UNKNOWN;
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant latest(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static Diagnostic diagnostic(DiagnosticCode code, Path path, int line, String message) {
        return new Diagnostic(code, DiagnosticSeverity.WARNING, path.toString(), line, message);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record ProbeRecord(String schema, String runId, String startedAt, String endedAt, String reason,
                               ProbeFilter filter, Integer edgeLimit, Long droppedEvents, List<ProbeEdge> edges) {
    }

    private record ProbeFilter(List<String> include, List<String> exclude) {
    }

    private record ProbeEdge(ProbeMethod caller, ProbeMethod callee, Long hits, String dispatch) {
    }

    private record ProbeMethod(String owner, String name, String descriptor) {
    }

    private record RuntimeRecord(String runId, Instant startedAt, Instant endedAt, List<String> filter,
                                 Path source, List<RuntimeEdge> edges) {
    }

    private record RuntimeEdge(MethodId caller, MethodId callee, long hits, DispatchKind dispatch) {
    }

    private record RecordKey(Path source, String runId) {
    }

    private record RuntimeFactKey(String runId, MethodId caller, MethodId callee) {
    }
}
