package dev.ghostmap.mcp;

import dev.ghostmap.model.CodeVersion;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP {@code who_injects} definition and snapshot-only handler. */
final class WhoInjectsTool {
    static final String NAME = "who_injects";
    static final Map<String, Object> INPUT_SCHEMA = inputSchema();
    static final Map<String, Object> OUTPUT_SCHEMA = outputSchema();

    private final SnapshotIndex index;
    private final McpSyncTool tool;

    WhoInjectsTool(SnapshotIndex index) {
        if (index == null) {
            throw new NullPointerException("index");
        }
        this.index = index;
        McpSchema.Tool definition = McpSchema.Tool.builder(NAME, INPUT_SCHEMA)
                .description("Find Spring beans that inject a bean or type, including XML parent inheritance.")
                .outputSchema(OUTPUT_SCHEMA)
                .build();
        this.tool = McpSyncTool.of(definition, (context, request) -> call(request.arguments()));
    }

    McpSyncTool specification() {
        return tool;
    }

    McpSyncResponse<McpSchema.CallToolOutcome> call(Map<String, Object> arguments) {
        Object rawQuery = arguments == null ? null : arguments.get("beanOrType");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return response(error("INVALID_ARGUMENT", "beanOrType must be a non-blank string", ""), true);
        }
        SnapshotIndex.Resolution resolution = index.resolve(query);
        return switch (resolution) {
            case SnapshotIndex.Resolution.NotFound missing -> response(
                    error("NOT_FOUND", "No bean or type matched '" + missing.query() + "'", missing.query()), true);
            case SnapshotIndex.Resolution.Ambiguous ambiguous -> {
                Map<String, Object> value = base("AMBIGUOUS", ambiguous.query());
                value.put("candidates", ambiguous.candidates().stream()
                        .map(SnapshotIndex::nodeReference).toList());
                value.put("error", Map.of(
                        "code", "AMBIGUOUS",
                        "message", "More than one bean or type matched '" + ambiguous.query() + "'"));
                yield response(value, true);
            }
            case SnapshotIndex.Resolution.Found found -> {
                Map<String, Object> value = base("OK", found.query());
                value.put("resolved", SnapshotIndex.nodeReference(found.node()));
                value.put("candidates", List.of());
                value.put("chains", index.injectionChains(found.node()));
                yield response(value, false);
            }
        };
    }

    private McpSyncResponse<McpSchema.CallToolOutcome> response(Map<String, Object> value, boolean isError) {
        String text = switch (value.get("status").toString()) {
            case "OK" -> "Injection chains for " + value.get("query");
            case "AMBIGUOUS" -> "Ambiguous bean or type: " + value.get("query");
            case "NOT_FOUND" -> "Bean or type not found: " + value.get("query");
            default -> "Invalid who_injects request";
        };
        CallToolResult result = CallToolResult.builder()
                .addContent(TextContent.builder(text).build())
                .structuredContent(Map.copyOf(value))
                .isError(isError)
                .build();
        return McpSyncResponse.result(result);
    }

    private Map<String, Object> error(String code, String message, String query) {
        Map<String, Object> value = base(code, query);
        value.put("candidates", List.of());
        value.put("error", Map.of("code", code, "message", message));
        return value;
    }

    private Map<String, Object> base(String status, String query) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("snapshotId", index.snapshot().snapshotId());
        value.put("codeVersion", codeVersion(index.snapshot().codeVersion()));
        value.put("status", status);
        value.put("query", query);
        return value;
    }

    private static Map<String, Object> codeVersion(CodeVersion version) {
        return Map.of(
                "repo", version.repo(),
                "commit", version.commit(),
                "dirty", version.dirty(),
                "classHash", version.classHash(),
                "analysedAt", version.analysedAt().toString());
    }

    private static Map<String, Object> inputSchema() {
        return objectSchema(Map.of(
                "beanOrType", Map.of(
                        "type", "string",
                        "minLength", 1,
                        "description", "Canonical b:/t: id, bean name, or type simple/binary name")),
                List.of("beanOrType"), false);
    }

    private static Map<String, Object> outputSchema() {
        Map<String, Object> node = objectSchema(Map.of(
                "id", Map.of("type", "string"),
                "kind", Map.of("type", "string", "enum", List.of("BEAN", "TYPE")),
                "name", Map.of("type", "string")),
                List.of("id", "kind", "name"), false);
        Map<String, Object> evidence = objectSchema(Map.of(
                "layer", Map.of("type", "string")), List.of("layer"), true);
        Map<String, Object> edge = objectSchema(Map.ofEntries(
                Map.entry("id", Map.of("type", "string")),
                Map.entry("kind", Map.of("type", "string", "enum", List.of("INJECTS", "BINDS"))),
                Map.entry("from", Map.of("type", "string")),
                Map.entry("to", Map.of("type", "string")),
                Map.entry("site", Map.of("type", "string")),
                Map.entry("provenance", arraySchema(Map.of("type", "string"))),
                Map.entry("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)),
                Map.entry("ruleIds", arraySchema(Map.of("type", "string"))),
                Map.entry("evidence", arraySchema(evidence)),
                Map.entry("dispatch", Map.of("type", "string")),
                Map.entry("candidates", arraySchema(Map.of("type", "string"))),
                Map.entry("status", Map.of("type", "string"))),
                List.of("id", "kind", "from", "to", "provenance", "confidence", "ruleIds", "evidence",
                        "dispatch", "candidates", "status"), false);
        Map<String, Object> chain = objectSchema(Map.of(
                "injector", node,
                "injected", node,
                "xmlParentInheritance", arraySchema(node),
                "injectionEdge", edge,
                "injectorBindings", arraySchema(edge),
                "injectedBindings", arraySchema(edge)),
                List.of("injector", "injected", "xmlParentInheritance", "injectionEdge", "injectorBindings",
                        "injectedBindings"), false);
        Map<String, Object> version = objectSchema(Map.of(
                "repo", Map.of("type", "string"),
                "commit", Map.of("type", "string"),
                "dirty", Map.of("type", "boolean"),
                "classHash", Map.of("type", "string"),
                "analysedAt", Map.of("type", "string", "format", "date-time")),
                List.of("repo", "commit", "dirty", "classHash", "analysedAt"), false);
        Map<String, Object> error = objectSchema(Map.of(
                "code", Map.of("type", "string"),
                "message", Map.of("type", "string")), List.of("code", "message"), false);
        return objectSchema(Map.of(
                "snapshotId", Map.of("type", "string"),
                "codeVersion", version,
                "status", Map.of("type", "string",
                        "enum", List.of("OK", "NOT_FOUND", "AMBIGUOUS", "INVALID_ARGUMENT")),
                "query", Map.of("type", "string"),
                "resolved", node,
                "candidates", arraySchema(node),
                "chains", arraySchema(chain),
                "error", error),
                List.of("snapshotId", "codeVersion", "status", "query"), false);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required,
                                                     boolean additionalProperties) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", additionalProperties);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }
}
