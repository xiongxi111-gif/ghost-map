package dev.ghostmap.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeUnitTest {
    @Test
    void parsesFiltersPathsLimitsAndRunIdentity(@TempDir Path directory) {
        ProbeOptions options = ProbeOptions.parse("include=example.app;example.shared,exclude=example.app.internal,"
                + "out=" + directory.resolve("edges.jsonl") + ",edgeLimit=17,"
                + "trigger=" + directory.resolve("dump.trigger") + ",triggerPollMillis=25,"
                + "log=" + directory.resolve("probe.log") + ",runId=unit-run");

        assertEquals(List.of("example.app", "example.shared"), options.includes());
        assertEquals(List.of("example.app.internal"), options.excludes());
        assertEquals(17, options.edgeLimit());
        assertEquals("unit-run", options.runId());
        assertTrue(options.includesType("example.app.Service"));
        assertFalse(options.includesType("example.app.internal.SecretService"));
        assertFalse(options.includesType("java.lang.String"));
        assertTrue(options.trigger().isPresent());
    }

    @Test
    void rejectsMissingUnknownDuplicateAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> ProbeOptions.parse("out=edges.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> ProbeOptions.parse("include=example,unknown=true"));
        assertThrows(IllegalArgumentException.class, () -> ProbeOptions.parse("include=example,include=other"));
        assertThrows(IllegalArgumentException.class, () -> ProbeOptions.parse("include=example,edgeLimit=0"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbeOptions.parse("include=example,triggerPollMillis=5"));
        assertThrows(IllegalArgumentException.class,
                () -> ProbeOptions.parse("include=example,out=same,log=same"));
    }

    @Test
    void concurrentStoreNeverExceedsLimitAndExistingPairsStillIncrement() throws InterruptedException {
        int limit = 7;
        BoundedEdgeStore store = new BoundedEdgeStore(limit);
        MethodIdentity commonCaller = method("sample.Caller", "call");
        MethodIdentity commonCallee = method("sample.Target", "common");
        store.record(commonCaller, commonCallee, RuntimeDispatchKind.UNKNOWN);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (int worker = 0; worker < 8; worker++) {
                int workerId = worker;
                workers.submit(() -> {
                    for (int index = 0; index < 100; index++) {
                        store.record(commonCaller, commonCallee, RuntimeDispatchKind.UNKNOWN);
                        store.record(commonCaller, method("sample.Target", "edge" + workerId + '_' + index),
                                RuntimeDispatchKind.UNKNOWN);
                    }
                });
            }
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(limit, store.size());
        assertTrue(store.droppedEvents() > 0);
        BoundedEdgeStore.RecordedEdge common = store.snapshot().stream()
                .filter(edge -> edge.key().callee().equals(commonCallee))
                .findFirst().orElseThrow();
        assertEquals(801, common.hits());
    }

    @Test
    void jsonlContainsOnlyMethodMetadataAndAggregateCounts(@TempDir Path directory) throws IOException {
        String secretArgument = "request-body-secret-value";
        Path output = directory.resolve("runtime-edges.jsonl");
        ProbeOptions options = ProbeOptions.parse("include=sample.app,out=" + output
                + ",log=" + directory.resolve("probe.log") + ",runId=privacy-run,edgeLimit=4");
        BoundedEdgeStore store = new BoundedEdgeStore(options.edgeLimit());
        store.record(method("sample.app.Caller", "call"), method("sample.app.Target", "work"),
                RuntimeDispatchKind.UNKNOWN);
        store.record(method("sample.app.Caller", "call"), method("sample.app.Target", "work"),
                RuntimeDispatchKind.UNKNOWN);

        new RuntimeDumpWriter(output).append(options, Instant.parse("2026-11-22T00:00:00Z"),
                Instant.parse("2026-11-22T00:00:01Z"), "shutdown", store);

        String jsonl = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(jsonl.startsWith("{\"schema\":\"ghostmap.runtime/v1\""));
        assertTrue(jsonl.contains("\"runId\":\"privacy-run\""));
        assertTrue(jsonl.contains("\"owner\":\"sample.app.Caller\""));
        assertTrue(jsonl.contains("\"descriptor\":\"()V\""));
        assertTrue(jsonl.contains("\"hits\":2"));
        assertFalse(jsonl.contains(secretArgument));
        assertFalse(jsonl.contains("arguments"));
        assertEquals(1, jsonl.lines().count());
    }

    private static MethodIdentity method(String owner, String name) {
        return new MethodIdentity(owner, name, "()V");
    }
}
