package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Evidence.Ast;
import dev.ghostmap.model.Evidence.Bytecode;
import dev.ghostmap.model.Evidence.Runtime;
import dev.ghostmap.model.Evidence.Wiring;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.NumberValue;
import dev.ghostmap.model.JsonValue.ObjectValue;
import dev.ghostmap.model.JsonValue.StringValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/** JDK-only JSON serialisation for the version-one snapshot schema. */
public final class SnapshotJson {
    private SnapshotJson() {
    }

    public static String toJson(Snapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        field(json, "schema", snapshot.schema());
        comma(json);
        field(json, "snapshotId", snapshot.snapshotId());
        comma(json);
        name(json, "codeVersion");
        writeCodeVersion(json, snapshot.codeVersion());
        comma(json);
        name(json, "tool");
        writeTool(json, snapshot.tool());
        comma(json);
        name(json, "layersRun");
        writeEnums(json, snapshot.layersRun());
        comma(json);
        name(json, "runtimeRuns");
        writeRuntimeRuns(json, snapshot.runtimeRuns());
        comma(json);
        name(json, "nodes");
        writeNodes(json, snapshot.nodes());
        comma(json);
        name(json, "edges");
        writeEdges(json, snapshot.edges());
        comma(json);
        name(json, "report");
        writeReport(json, snapshot.report());
        json.append('}');
        return json.toString();
    }

    public static Snapshot fromJson(String json) {
        Map<String, Object> root = object(new JsonReader(json).read(), "snapshot");
        String schema = string(required(root, "schema"), "schema");
        String snapshotId = string(required(root, "snapshotId"), "snapshotId");
        CodeVersion codeVersion = readCodeVersion(object(required(root, "codeVersion"), "codeVersion"));
        ToolInfo tool = readTool(object(required(root, "tool"), "tool"));
        Set<LayerId> layersRun = readEnumSet(array(required(root, "layersRun"), "layersRun"), LayerId.class,
                "layersRun");
        List<RuntimeRun> runtimeRuns = readRuntimeRuns(array(required(root, "runtimeRuns"), "runtimeRuns"));
        List<Node> nodes = readNodes(array(required(root, "nodes"), "nodes"));
        List<Edge> edges = readEdges(array(required(root, "edges"), "edges"));
        BlindSpotReport report = readReport(object(required(root, "report"), "report"));
        return new Snapshot(schema, snapshotId, codeVersion, tool, layersRun, runtimeRuns, nodes, edges, report);
    }

    private static void writeCodeVersion(StringBuilder json, CodeVersion version) {
        json.append('{');
        field(json, "repo", version.repo());
        comma(json);
        field(json, "commit", version.commit());
        comma(json);
        field(json, "dirty", version.dirty());
        comma(json);
        field(json, "classHash", version.classHash());
        comma(json);
        field(json, "analysedAt", version.analysedAt().toString());
        json.append('}');
    }

    private static CodeVersion readCodeVersion(Map<String, Object> value) {
        return new CodeVersion(
                string(required(value, "repo"), "codeVersion.repo"),
                string(required(value, "commit"), "codeVersion.commit"),
                bool(required(value, "dirty"), "codeVersion.dirty"),
                string(required(value, "classHash"), "codeVersion.classHash"),
                Instant.parse(string(required(value, "analysedAt"), "codeVersion.analysedAt")));
    }

    private static void writeTool(StringBuilder json, ToolInfo tool) {
        json.append('{');
        field(json, "name", tool.name());
        comma(json);
        field(json, "version", tool.version());
        comma(json);
        field(json, "joinPolicy", tool.joinPolicy());
        json.append('}');
    }

    private static ToolInfo readTool(Map<String, Object> value) {
        return new ToolInfo(
                string(required(value, "name"), "tool.name"),
                string(required(value, "version"), "tool.version"),
                string(required(value, "joinPolicy"), "tool.joinPolicy"));
    }

    private static void writeRuntimeRuns(StringBuilder json, List<RuntimeRun> runs) {
        json.append('[');
        for (int index = 0; index < runs.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            RuntimeRun run = runs.get(index);
            json.append('{');
            field(json, "runId", run.runId());
            comma(json);
            field(json, "workload", run.workload());
            comma(json);
            name(json, "filter");
            writeStrings(json, run.filter());
            json.append('}');
        }
        json.append(']');
    }

    private static List<RuntimeRun> readRuntimeRuns(List<Object> values) {
        List<RuntimeRun> runs = new ArrayList<>(values.size());
        for (Object item : values) {
            Map<String, Object> run = object(item, "runtime run");
            runs.add(new RuntimeRun(
                    string(required(run, "runId"), "runtimeRun.runId"),
                    string(required(run, "workload"), "runtimeRun.workload"),
                    readStrings(array(required(run, "filter"), "runtimeRun.filter"), "runtimeRun.filter")));
        }
        return List.copyOf(runs);
    }

    private static void writeNodes(StringBuilder json, List<Node> nodes) {
        json.append('[');
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            Node node = nodes.get(index);
            json.append('{');
            field(json, "id", node.id().value());
            comma(json);
            field(json, "kind", node.kind().name());
            comma(json);
            field(json, "name", node.name());
            comma(json);
            name(json, "owner");
            writeOptionalString(json, node.owner().map(CanonicalId::value));
            comma(json);
            name(json, "attrs");
            writeAttributes(json, node.attrs());
            comma(json);
            name(json, "loc");
            writeLocation(json, node.location());
            json.append('}');
        }
        json.append(']');
    }

    private static List<Node> readNodes(List<Object> values) {
        List<Node> nodes = new ArrayList<>(values.size());
        for (Object item : values) {
            Map<String, Object> node = object(item, "node");
            CanonicalId id = CanonicalIds.parse(string(required(node, "id"), "node.id"));
            NodeKind kind = enumValue(NodeKind.class, required(node, "kind"), "node.kind");
            Optional<TypeId> owner = optionalString(required(node, "owner"), "node.owner")
                    .map(CanonicalIds::parse)
                    .map(parsed -> {
                        if (parsed instanceof TypeId typeId) {
                            return typeId;
                        }
                        throw new IllegalArgumentException("node.owner must be a type id");
                    });
            Map<String, JsonValue> attrs = readAttributes(object(required(node, "attrs"), "node.attrs"));
            Optional<SourceLocation> location = readOptionalLocation(required(node, "loc"));
            nodes.add(new Node(id, kind, string(required(node, "name"), "node.name"), owner, attrs, location));
        }
        return List.copyOf(nodes);
    }

    private static void writeAttributes(StringBuilder json, Map<String, JsonValue> attrs) {
        json.append('{');
        int index = 0;
        for (Map.Entry<String, JsonValue> entry : new TreeMap<>(attrs).entrySet()) {
            if (index++ > 0) {
                comma(json);
            }
            name(json, entry.getKey());
            writeJsonValue(json, entry.getValue());
        }
        json.append('}');
    }

    private static Map<String, JsonValue> readAttributes(Map<String, Object> attrs) {
        Map<String, JsonValue> result = new LinkedHashMap<>();
        attrs.forEach((key, value) -> result.put(key, readJsonValue(value, "node.attrs." + key)));
        return Map.copyOf(result);
    }

    private static void writeJsonValue(StringBuilder json, JsonValue value) {
        switch (value) {
            case StringValue stringValue -> quote(json, stringValue.value());
            case NumberValue numberValue -> json.append(numberValue.value().toPlainString());
            case BooleanValue booleanValue -> json.append(booleanValue.value());
            case ArrayValue arrayValue -> {
                json.append('[');
                for (int index = 0; index < arrayValue.values().size(); index++) {
                    if (index > 0) {
                        comma(json);
                    }
                    writeJsonValue(json, arrayValue.values().get(index));
                }
                json.append(']');
            }
            case ObjectValue objectValue -> writeAttributes(json, objectValue.values());
        }
    }

    private static JsonValue readJsonValue(Object value, String path) {
        if (value instanceof String stringValue) {
            return new StringValue(stringValue);
        }
        if (value instanceof BigDecimal numberValue) {
            return new NumberValue(numberValue);
        }
        if (value instanceof Boolean booleanValue) {
            return new BooleanValue(booleanValue);
        }
        if (value instanceof List<?>) {
            List<JsonValue> items = new ArrayList<>();
            for (Object item : array(value, path)) {
                items.add(readJsonValue(item, path));
            }
            return new ArrayValue(items);
        }
        if (value instanceof Map<?, ?>) {
            return new ObjectValue(readAttributes(object(value, path)));
        }
        throw new IllegalArgumentException(path + " must not contain null");
    }

    private static void writeLocation(StringBuilder json, Optional<SourceLocation> location) {
        if (location.isEmpty()) {
            json.append("null");
            return;
        }
        SourceLocation value = location.orElseThrow();
        json.append('{');
        field(json, "file", value.file());
        comma(json);
        field(json, "line", value.line());
        comma(json);
        field(json, "endLine", value.endLine());
        json.append('}');
    }

    private static Optional<SourceLocation> readOptionalLocation(Object value) {
        if (value == JsonNull.VALUE) {
            return Optional.empty();
        }
        Map<String, Object> location = object(value, "node.loc");
        return Optional.of(new SourceLocation(
                string(required(location, "file"), "node.loc.file"),
                integer(required(location, "line"), "node.loc.line"),
                integer(required(location, "endLine"), "node.loc.endLine")));
    }

    private static void writeEdges(StringBuilder json, List<Edge> edges) {
        json.append('[');
        for (int index = 0; index < edges.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            writeEdge(json, edges.get(index));
        }
        json.append(']');
    }

    private static void writeEdge(StringBuilder json, Edge edge) {
        json.append('{');
        field(json, "id", edge.id().value());
        comma(json);
        field(json, "kind", edge.kind().name());
        comma(json);
        field(json, "from", edge.from().value());
        comma(json);
        name(json, "to");
        writeOptionalString(json, edge.target().map(CanonicalId::value));
        comma(json);
        name(json, "site");
        writeOptionalString(json, edge.site().map(CanonicalId::value));
        comma(json);
        name(json, "provenance");
        writeEnums(json, edge.provenance());
        comma(json);
        field(json, "confidence", edge.confidence());
        comma(json);
        name(json, "rules");
        writeStrings(json, edge.rules());
        comma(json);
        name(json, "evidence");
        writeEvidenceList(json, edge.evidence());
        comma(json);
        field(json, "dispatch", edge.dispatch().name());
        comma(json);
        name(json, "candidates");
        writeCanonicalIds(json, edge.candidates());
        comma(json);
        field(json, "status", edge.status().name());
        if (edge instanceof Edge.Unresolved unresolved) {
            comma(json);
            name(json, "partialKey");
            writeOptionalString(json, unresolved.partialKey());
        }
        json.append('}');
    }

    private static List<Edge> readEdges(List<Object> values) {
        List<Edge> edges = new ArrayList<>(values.size());
        for (Object item : values) {
            Map<String, Object> edge = object(item, "edge");
            EdgeId id = new EdgeId(string(required(edge, "id"), "edge.id"));
            EdgeKind kind = enumValue(EdgeKind.class, required(edge, "kind"), "edge.kind");
            CanonicalId from = CanonicalIds.parse(string(required(edge, "from"), "edge.from"));
            Optional<CanonicalId> target = optionalString(required(edge, "to"), "edge.to").map(CanonicalIds::parse);
            Optional<SiteId> site = optionalString(required(edge, "site"), "edge.site")
                    .map(CanonicalIds::parse)
                    .map(parsed -> {
                        if (parsed instanceof SiteId siteId) {
                            return siteId;
                        }
                        throw new IllegalArgumentException("edge.site must be a site id");
                    });
            Set<LayerId> provenance = readEnumSet(array(required(edge, "provenance"), "edge.provenance"),
                    LayerId.class, "edge.provenance");
            double confidence = decimal(required(edge, "confidence"), "edge.confidence").doubleValue();
            List<String> rules = readStrings(array(required(edge, "rules"), "edge.rules"), "edge.rules");
            List<Evidence> evidence = readEvidenceList(array(required(edge, "evidence"), "edge.evidence"));
            DispatchKind dispatch = enumValue(DispatchKind.class, required(edge, "dispatch"), "edge.dispatch");
            List<CanonicalId> candidates = readCanonicalIds(array(required(edge, "candidates"), "edge.candidates"));
            EdgeStatus status = enumValue(EdgeStatus.class, required(edge, "status"), "edge.status");
            if (kind == EdgeKind.UNRESOLVED) {
                if (target.isPresent() || site.isEmpty() || status != EdgeStatus.UNRESOLVED) {
                    throw new IllegalArgumentException("invalid unresolved edge");
                }
                Optional<String> partialKey = edge.containsKey("partialKey")
                        ? optionalString(required(edge, "partialKey"), "edge.partialKey") : Optional.empty();
                edges.add(new Edge.Unresolved(id, from, site.orElseThrow(), provenance, confidence, rules,
                        evidence, dispatch, candidates, partialKey));
            } else {
                edges.add(new Edge.Resolved(id, kind, from, target.orElseThrow(
                        () -> new IllegalArgumentException("resolved edge requires to")), site, provenance,
                        confidence, rules, evidence, dispatch, candidates, status));
            }
        }
        return List.copyOf(edges);
    }

    private static void writeEvidenceList(StringBuilder json, List<Evidence> evidence) {
        json.append('[');
        for (int index = 0; index < evidence.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            Evidence item = evidence.get(index);
            json.append('{');
            field(json, "layer", item.layer().name());
            switch (item) {
                case Ast ast -> {
                    comma(json);
                    field(json, "file", ast.file());
                    comma(json);
                    field(json, "line", ast.line());
                    comma(json);
                    field(json, "text", ast.text());
                }
                case Bytecode bytecode -> {
                    comma(json);
                    field(json, "class", bytecode.classFile());
                    comma(json);
                    field(json, "offset", bytecode.offset());
                    comma(json);
                    field(json, "insn", bytecode.instruction());
                    comma(json);
                    field(json, "owner", bytecode.owner());
                    comma(json);
                    field(json, "name", bytecode.name());
                    comma(json);
                    field(json, "desc", bytecode.descriptor());
                }
                case Wiring wiring -> {
                    comma(json);
                    field(json, "file", wiring.file());
                    comma(json);
                    field(json, "line", wiring.line());
                    comma(json);
                    field(json, "bean", wiring.bean().value());
                }
                case Runtime runtime -> {
                    comma(json);
                    field(json, "runId", runtime.runId());
                    comma(json);
                    field(json, "hits", runtime.hits());
                }
            }
            json.append('}');
        }
        json.append(']');
    }

    private static List<Evidence> readEvidenceList(List<Object> values) {
        List<Evidence> evidence = new ArrayList<>(values.size());
        for (Object item : values) {
            Map<String, Object> value = object(item, "evidence");
            LayerId layer = enumValue(LayerId.class, required(value, "layer"), "evidence.layer");
            Evidence parsed = switch (layer) {
                case AST -> new Ast(
                        string(required(value, "file"), "evidence.file"),
                        integer(required(value, "line"), "evidence.line"),
                        string(required(value, "text"), "evidence.text"));
                case BYTECODE -> new Bytecode(
                        string(required(value, "class"), "evidence.class"),
                        integer(required(value, "offset"), "evidence.offset"),
                        string(required(value, "insn"), "evidence.insn"),
                        string(required(value, "owner"), "evidence.owner"),
                        string(required(value, "name"), "evidence.name"),
                        string(required(value, "desc"), "evidence.desc"));
                case WIRING -> {
                    CanonicalId bean = CanonicalIds.parse(string(required(value, "bean"), "evidence.bean"));
                    if (!(bean instanceof BeanId beanId)) {
                        throw new IllegalArgumentException("evidence.bean must be a bean id");
                    }
                    yield new Wiring(
                            string(required(value, "file"), "evidence.file"),
                            integer(required(value, "line"), "evidence.line"), beanId);
                }
                case RUNTIME -> new Runtime(
                        string(required(value, "runId"), "evidence.runId"),
                        longValue(required(value, "hits"), "evidence.hits"));
            };
            evidence.add(parsed);
        }
        return List.copyOf(evidence);
    }

    private static void writeReport(StringBuilder json, BlindSpotReport report) {
        json.append('{');
        name(json, "edgesByProvenance");
        writeStringLongMap(json, report.edgesByProvenance());
        comma(json);
        name(json, "unresolvedSites");
        Map<String, Long> unresolved = new TreeMap<>();
        report.unresolvedSites().forEach((key, value) -> unresolved.put(key.name(), value));
        writeStringLongMap(json, unresolved);
        comma(json);
        field(json, "phantomEdges", report.phantomEdges());
        comma(json);
        name(json, "exercised");
        json.append('{');
        field(json, "methodsExercised", report.exercised().methodsExercised());
        comma(json);
        field(json, "methodsInScope", report.exercised().methodsInScope());
        json.append('}');
        comma(json);
        name(json, "staticRecallOnExercised");
        json.append('{');
        name(json, "astOnly");
        writeOptionalDouble(json, report.staticRecallOnExercised().astOnly());
        comma(json);
        name(json, "ghostMap");
        writeOptionalDouble(json, report.staticRecallOnExercised().ghostMap());
        json.append('}');
        comma(json);
        field(json, "caveat", report.caveat());
        json.append('}');
    }

    private static BlindSpotReport readReport(Map<String, Object> value) {
        Map<String, Long> edgesByProvenance = readStringLongMap(
                object(required(value, "edgesByProvenance"), "report.edgesByProvenance"));
        Map<String, Long> unresolvedValues = readStringLongMap(
                object(required(value, "unresolvedSites"), "report.unresolvedSites"));
        Map<UnresolvedSiteKind, Long> unresolvedSites = new LinkedHashMap<>();
        unresolvedValues.forEach((key, count) -> unresolvedSites.put(UnresolvedSiteKind.valueOf(key), count));
        Map<String, Object> exercised = object(required(value, "exercised"), "report.exercised");
        Map<String, Object> recall = object(required(value, "staticRecallOnExercised"),
                "report.staticRecallOnExercised");
        return new BlindSpotReport(
                edgesByProvenance,
                unresolvedSites,
                longValue(required(value, "phantomEdges"), "report.phantomEdges"),
                new ExercisedCoverage(
                        longValue(required(exercised, "methodsExercised"), "report.methodsExercised"),
                        longValue(required(exercised, "methodsInScope"), "report.methodsInScope")),
                new StaticRecall(
                        optionalDouble(required(recall, "astOnly"), "report.astOnly"),
                        optionalDouble(required(recall, "ghostMap"), "report.ghostMap")),
                string(required(value, "caveat"), "report.caveat"));
    }

    private static void writeStringLongMap(StringBuilder json, Map<String, Long> values) {
        json.append('{');
        int index = 0;
        for (Map.Entry<String, Long> entry : new TreeMap<>(values).entrySet()) {
            if (index++ > 0) {
                comma(json);
            }
            field(json, entry.getKey(), entry.getValue());
        }
        json.append('}');
    }

    private static Map<String, Long> readStringLongMap(Map<String, Object> value) {
        Map<String, Long> result = new LinkedHashMap<>();
        value.forEach((key, count) -> result.put(key, longValue(count, key)));
        return Map.copyOf(result);
    }

    private static void writeCanonicalIds(StringBuilder json, List<CanonicalId> ids) {
        writeStrings(json, ids.stream().map(CanonicalId::value).toList());
    }

    private static List<CanonicalId> readCanonicalIds(List<Object> values) {
        return readStrings(values, "canonical ids").stream().map(CanonicalIds::parse).toList();
    }

    private static void writeStrings(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                comma(json);
            }
            quote(json, values.get(index));
        }
        json.append(']');
    }

    private static List<String> readStrings(List<Object> values, String path) {
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(string(value, path));
        }
        return List.copyOf(result);
    }

    private static void writeEnums(StringBuilder json, Set<? extends Enum<?>> values) {
        List<String> names = values.stream().sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name).toList();
        writeStrings(json, names);
    }

    private static <E extends Enum<E>> Set<E> readEnumSet(List<Object> values, Class<E> type, String path) {
        Set<E> result = EnumSet.noneOf(type);
        for (Object value : values) {
            result.add(enumValue(type, value, path));
        }
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String path) {
        String name = string(value, path);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + " has unknown value " + name, exception);
        }
    }

    private static Optional<String> optionalString(Object value, String path) {
        return value == JsonNull.VALUE ? Optional.empty() : Optional.of(string(value, path));
    }

    private static OptionalDouble optionalDouble(Object value, String path) {
        return value == JsonNull.VALUE ? OptionalDouble.empty()
                : OptionalDouble.of(decimal(value, path).doubleValue());
    }

    private static void writeOptionalString(StringBuilder json, Optional<String> value) {
        if (value.isPresent()) {
            quote(json, value.orElseThrow());
        } else {
            json.append("null");
        }
    }

    private static void writeOptionalDouble(StringBuilder json, OptionalDouble value) {
        if (value.isPresent()) {
            json.append(Double.toString(value.orElseThrow()));
        } else {
            json.append("null");
        }
    }

    private static Object required(Map<String, Object> object, String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("missing required JSON field: " + key);
        }
        return object.get(key);
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException(path + " has a non-string key");
            }
            result.put(stringKey, item);
        });
        return result;
    }

    private static List<Object> array(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return string;
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return bool;
    }

    private static BigDecimal decimal(Object value, String path) {
        if (!(value instanceof BigDecimal decimal)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        return decimal;
    }

    private static int integer(Object value, String path) {
        try {
            return decimal(value, path).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(path + " must be an integer", exception);
        }
    }

    private static long longValue(Object value, String path) {
        try {
            return decimal(value, path).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(path + " must be an integer", exception);
        }
    }

    private static void name(StringBuilder json, String name) {
        quote(json, name);
        json.append(':');
    }

    private static void field(StringBuilder json, String name, String value) {
        name(json, name);
        quote(json, value);
    }

    private static void field(StringBuilder json, String name, boolean value) {
        name(json, name);
        json.append(value);
    }

    private static void field(StringBuilder json, String name, long value) {
        name(json, name);
        json.append(value);
    }

    private static void field(StringBuilder json, String name, double value) {
        name(json, name);
        json.append(Double.toString(value));
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

    private enum JsonNull {
        VALUE
    }

    private static final class JsonReader {
        private final String input;
        private int position;

        private JsonReader(String input) {
            if (input == null) {
                throw new NullPointerException("json");
            }
            this.input = input;
        }

        private Object read() {
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (position != input.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (position >= input.length()) {
                throw error("expected a value");
            }
            return switch (input.charAt(position)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", JsonNull.VALUE);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    throw error("expected an object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                Object previous = object.putIfAbsent(key, readValue());
                if (previous != null) {
                    throw error("duplicate object key: " + key);
                }
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return values;
            }
            while (true) {
                values.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw error("unterminated escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(readUnicodeEscape());
                        default -> throw error("invalid escape");
                    }
                } else {
                    if (character < 0x20) {
                        throw error("unescaped control character");
                    }
                    value.append(character);
                }
            }
            throw error("unterminated string");
        }

        private char readUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("incomplete unicode escape");
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private BigDecimal readNumber() {
            int start = position;
            consume('-');
            if (consume('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    throw error("leading zero in number");
                }
            } else {
                readDigits();
            }
            if (consume('.')) {
                readDigits();
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                position++;
                if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                    position++;
                }
                readDigits();
            }
            if (position == start) {
                throw error("invalid value");
            }
            try {
                return new BigDecimal(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("invalid number", exception);
            }
        }

        private void readDigits() {
            int start = position;
            while (position < input.length() && Character.isDigit(input.charAt(position))) {
                position++;
            }
            if (position == start) {
                throw error("expected a digit");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) {
                throw error("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (position < input.length()) {
                char character = input.charAt(position);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON offset " + position);
        }

        private IllegalArgumentException error(String message, Exception cause) {
            return new IllegalArgumentException(message + " at JSON offset " + position, cause);
        }
    }
}
