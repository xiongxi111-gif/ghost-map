package dev.ghostmap.mcp;

import dev.ghostmap.model.BlindSpotReport;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.CodeVersion;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeIds;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.Evidence;
import dev.ghostmap.model.ExercisedCoverage;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.Node;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.Snapshot;
import dev.ghostmap.model.SnapshotJson;
import dev.ghostmap.model.StaticRecall;
import dev.ghostmap.model.ToolInfo;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhoInjectsToolTest {
    private static final String SNAPSHOT_HASH = "sha256:" + "1".repeat(64);
    private static final String CLASS_HASH = "sha256:" + "2".repeat(64);

    @Test
    void listsOnlyWhoInjectsWithInputAndOutputSchemas() {
        GhostMapToolRepository repository = repository();

        ToolsPage page = repository.list(null, null);

        assertEquals(1, page.tools().size());
        McpSchema.Tool definition = page.tools().getFirst();
        assertEquals("who_injects", definition.name());
        assertEquals(List.of("beanOrType"), definition.inputSchema().get("required"));
        assertNotNull(definition.outputSchema());
        Map<?, ?> properties = assertInstanceOf(Map.class, definition.outputSchema().get("properties"));
        assertTrue(properties.containsKey("snapshotId"));
        assertTrue(properties.containsKey("codeVersion"));
        assertTrue(properties.containsKey("chains"));
        assertNotNull(repository.find(null, "who_injects"));
        assertNull(repository.find(null, "get_callers"));
    }

    @Test
    void returnsInjectionEvidenceAndXmlParentInheritance() {
        Map<String, Object> result = call("b:app.xml#service");

        assertEquals("OK", result.get("status"));
        assertEquals(SNAPSHOT_HASH, result.get("snapshotId"));
        Map<?, ?> version = assertInstanceOf(Map.class, result.get("codeVersion"));
        assertEquals("deadbeef", version.get("commit"));
        List<?> chains = assertInstanceOf(List.class, result.get("chains"));
        assertEquals(1, chains.size());
        Map<?, ?> chain = assertInstanceOf(Map.class, chains.getFirst());
        List<?> inheritance = assertInstanceOf(List.class, chain.get("xmlParentInheritance"));
        assertEquals(List.of("b:app.xml#childClient", "b:app.xml#baseClient"), inheritance.stream()
                .map(Map.class::cast).map(item -> item.get("id")).toList());

        Map<?, ?> injection = assertInstanceOf(Map.class, chain.get("injectionEdge"));
        assertEquals(List.of("WIRING"), injection.get("provenance"));
        assertEquals(0.8, injection.get("confidence"));
        assertEquals(List.of("R05"), injection.get("ruleIds"));
        List<?> evidence = assertInstanceOf(List.class, injection.get("evidence"));
        Map<?, ?> wiring = assertInstanceOf(Map.class, evidence.getFirst());
        assertEquals("app.xml", wiring.get("file"));
        assertEquals(12, wiring.get("line"));

        List<?> injectedBindings = assertInstanceOf(List.class, chain.get("injectedBindings"));
        Map<?, ?> binding = assertInstanceOf(Map.class, injectedBindings.getFirst());
        assertEquals(List.of("WIRING"), binding.get("provenance"));
        assertFalse(assertInstanceOf(List.class, binding.get("ruleIds")).isEmpty());
        assertFalse(assertInstanceOf(List.class, binding.get("evidence")).isEmpty());
    }

    @Test
    void resolvesCanonicalTypeAndUniqueBinaryOrSimpleNames() {
        assertEquals("OK", call("t:ghost.synthetic.PaymentService").get("status"));
        assertEquals("OK", call("ghost.synthetic.PaymentService").get("status"));
        assertEquals("OK", call("PaymentClient").get("status"));
        assertEquals("BEAN", assertInstanceOf(Map.class, call("childClient").get("resolved")).get("kind"));
    }

    @Test
    void returnsCandidatesInsteadOfGuessingForAmbiguousSimpleName() {
        ToolOutcome outcome = invoke("PaymentService");

        assertTrue(outcome.isError());
        assertEquals("AMBIGUOUS", outcome.structured().get("status"));
        List<?> candidates = assertInstanceOf(List.class, outcome.structured().get("candidates"));
        assertEquals(List.of("t:ghost.other.PaymentService", "t:ghost.synthetic.PaymentService"), candidates.stream()
                .map(Map.class::cast).map(item -> item.get("id")).toList());
        Map<?, ?> error = assertInstanceOf(Map.class, outcome.structured().get("error"));
        assertEquals("AMBIGUOUS", error.get("code"));
    }

    @Test
    void returnsStructuredNotFoundResult() {
        ToolOutcome outcome = invoke("MissingService");

        assertTrue(outcome.isError());
        assertEquals("NOT_FOUND", outcome.structured().get("status"));
        assertEquals(SNAPSHOT_HASH, outcome.structured().get("snapshotId"));
        Map<?, ?> error = assertInstanceOf(Map.class, outcome.structured().get("error"));
        assertEquals("NOT_FOUND", error.get("code"));
        assertTrue(assertInstanceOf(List.class, outcome.structured().get("candidates")).isEmpty());
    }

    @Test
    void answersFromSnapshotWithoutTargetClassLoadingOrSpringBoot() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("ghost.synthetic.PaymentService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.context.ApplicationContext"));

        assertEquals("OK", call("ghost.synthetic.PaymentService").get("status"));
    }

    @Test
    void loadsSnapshotFromConfiguredPath(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("synthetic.ghostmap.json");
        Files.writeString(path, SnapshotJson.toJson(snapshot()), StandardCharsets.UTF_8);

        Snapshot loaded = GhostMapMcpServer.loadSnapshot(GhostMapMcpServer.snapshotPath(
                new String[]{"--snapshot", path.toString()}));

        assertEquals(SNAPSHOT_HASH, loaded.snapshotId());
        assertThrows(IllegalArgumentException.class, () -> GhostMapMcpServer.snapshotPath(new String[]{}));
    }

    @Test
    void listsToolOverRealStdioTransport() throws Exception {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        McpServer server = GhostMapMcpServer.createServer(snapshot(), mapper);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PipedInputStream serverInput = new PipedInputStream();
             PipedOutputStream clientOutput = new PipedOutputStream(serverInput)) {
            StdioMcpTransport transport = new StdioMcpTransport(server, mapper, serverInput, output);
            transport.start().subscribe();
            String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{"
                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}\n";
            clientOutput.write(request.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();

            String listResponse = awaitLine(output, 1, Duration.ofSeconds(5));
            Map<String, Object> json = mapper.readValue(listResponse, new TypeRef<>() {
            });
            Map<?, ?> rpcResult = assertInstanceOf(Map.class, json.get("result"));
            List<?> tools = assertInstanceOf(List.class, rpcResult.get("tools"));
            assertEquals("who_injects", assertInstanceOf(Map.class, tools.getFirst()).get("name"));
            assertTrue(assertInstanceOf(Map.class, tools.getFirst()).containsKey("inputSchema"));

            String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                    + "\"name\":\"who_injects\",\"arguments\":{\"beanOrType\":\"childClient\"},"
                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}\n";
            clientOutput.write(call.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            String callResponse = awaitLine(output, 2, Duration.ofSeconds(5));
            Map<String, Object> callJson = mapper.readValue(callResponse, new TypeRef<>() {
            });
            Map<?, ?> callResult = assertInstanceOf(Map.class, callJson.get("result"));
            Map<?, ?> structured = assertInstanceOf(Map.class, callResult.get("structuredContent"));
            assertEquals("OK", structured.get("status"));
            assertEquals(SNAPSHOT_HASH, structured.get("snapshotId"));

            transport.closeGracefully().block(Duration.ofSeconds(5));
            server.closeGracefully();
        }
    }

    private static String awaitLine(ByteArrayOutputStream output, int lineNumber, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String value = output.toString(StandardCharsets.UTF_8);
            String[] lines = value.split("\\n", -1);
            if (lines.length > lineNumber) {
                return lines[lineNumber - 1];
            }
            Thread.sleep(10);
        }
        throw new AssertionError("stdio server did not respond before timeout");
    }

    private static Map<String, Object> call(String query) {
        return invoke(query).structured();
    }

    private static ToolOutcome invoke(String query) {
        McpSyncTool tool = repository().find(null, "who_injects");
        McpSyncResponse<McpSchema.CallToolOutcome> response = tool.handler().call(null,
                McpSchema.CallToolRequest.builder("who_injects")
                        .arguments(Map.of("beanOrType", query))
                        .build());
        McpSyncResponse.Result<?> synchronous = assertInstanceOf(McpSyncResponse.Result.class, response);
        McpSchema.CallToolResult result = assertInstanceOf(McpSchema.CallToolResult.class, synchronous.result());
        Map<?, ?> raw = assertInstanceOf(Map.class, result.structuredContent());
        Map<String, Object> structured = raw.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toString(), Map.Entry::getValue));
        return new ToolOutcome(Boolean.TRUE.equals(result.isError()), structured);
    }

    private static GhostMapToolRepository repository() {
        return new GhostMapToolRepository(new SnapshotIndex(snapshot()));
    }

    private static Snapshot snapshot() {
        TypeId clientType = new TypeId("ghost.synthetic.PaymentClient");
        TypeId serviceType = new TypeId("ghost.synthetic.PaymentService");
        TypeId sameSimpleName = new TypeId("ghost.other.PaymentService");
        BeanId base = new BeanId("app.xml", "baseClient");
        BeanId child = new BeanId("app.xml", "childClient");
        BeanId service = new BeanId("app.xml", "service");

        List<Node> nodes = List.of(
                type(clientType),
                type(serviceType),
                type(sameSimpleName),
                bean(base, Map.of()),
                bean(child, Map.of("parent", new StringValue("baseClient"))),
                bean(service, Map.of()));
        List<Edge> edges = List.of(
                wiringEdge(EdgeKind.BINDS, base, clientType, new Evidence.Wiring("app.xml", 4, base)),
                wiringEdge(EdgeKind.BINDS, child, clientType, new Evidence.Wiring("app.xml", 18, child)),
                wiringEdge(EdgeKind.BINDS, service, serviceType, new Evidence.Wiring("app.xml", 25, service)),
                wiringEdge(EdgeKind.INJECTS, child, service, new Evidence.Wiring("app.xml", 12, service)));
        return new Snapshot(
                Snapshot.SCHEMA,
                SNAPSHOT_HASH,
                new CodeVersion("https://example.invalid/synthetic", "deadbeef", false, CLASS_HASH,
                        Instant.parse("2026-11-22T00:00:00Z")),
                new ToolInfo("ghostmap", "0.1.0", "jp/v1"),
                Set.of(LayerId.WIRING),
                List.of(),
                nodes,
                edges,
                new BlindSpotReport(Map.of("WIRING", 4L), Map.of(), 0,
                        new ExercisedCoverage(0, 0),
                        new StaticRecall(OptionalDouble.empty(), OptionalDouble.empty()),
                        "No runtime workload was recorded for this synthetic snapshot."));
    }

    private static Node type(TypeId id) {
        return new Node(id, NodeKind.TYPE, id.binaryName().substring(id.binaryName().lastIndexOf('.') + 1),
                Optional.empty(), Map.of(), Optional.empty());
    }

    private static Node bean(BeanId id, Map<String, dev.ghostmap.model.JsonValue> attrs) {
        return new Node(id, NodeKind.BEAN, id.beanId(), Optional.empty(), attrs, Optional.empty());
    }

    private static Edge.Resolved wiringEdge(EdgeKind kind, dev.ghostmap.model.CanonicalId from,
                                             dev.ghostmap.model.CanonicalId to, Evidence.Wiring evidence) {
        return new Edge.Resolved(
                EdgeIds.canonical(kind, from, Optional.of(to), Optional.empty()),
                kind,
                from,
                to,
                Optional.empty(),
                Set.of(LayerId.WIRING),
                0.8,
                List.of("R05"),
                List.of(evidence),
                DispatchKind.STATIC,
                List.of(),
                EdgeStatus.PREDICTED);
    }

    private record ToolOutcome(boolean isError, Map<String, Object> structured) {
    }
}
