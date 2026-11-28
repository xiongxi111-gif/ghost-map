package dev.ghostmap.core.layer.runtime;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.LayerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLayerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void readsTrimmedAuthenticFrameworkPetclinicRecord() throws Exception {
        Path fixture = resource("/runtime/spring-framework-petclinic-trimmed.jsonl");

        var facts = new RuntimeLayer().analyse(LayerInput.forRuntimeFiles(fixture));
        List<RuntimeCallFact> calls = facts.edges().stream().map(RuntimeCallFact.class::cast).toList();

        assertEquals(LayerId.RUNTIME, facts.layer());
        assertTrue(facts.diagnostics().isEmpty());
        assertEquals(2, calls.size());
        assertEquals(3, facts.nodes().size());
        RuntimeCallFact first = calls.getFirst();
        assertEquals(new MethodId("org.springframework.samples.petclinic.model.Owner", "addPet",
                "(Lorg/springframework/samples/petclinic/model/Pet;)V"), first.caller());
        assertEquals(33, first.hits());
        assertEquals(DispatchKind.REFLECTION, first.dispatch());
        assertEquals("spring-framework-petclinic-20261122T052652Z", first.runId());
        assertEquals(List.of("org.springframework.samples"), first.filter());
        assertEquals(1, RuntimeLayer.runtimeRuns(facts).size());
        assertEquals("spring-framework-petclinic-trimmed.jsonl",
                RuntimeLayer.runtimeRuns(facts).getFirst().workload());
    }

    @Test
    void reportsMalformedUnsupportedAndMissingInputsWithoutThrowing() throws Exception {
        Path input = temporaryDirectory.resolve("invalid.jsonl");
        Files.writeString(input, "{not json}\n"
                + "{\"schema\":\"other/v1\",\"runId\":\"x\"}\n"
                + "{\"schema\":\"ghostmap.runtime/v1\",\"runId\":\"x\","
                + "\"startedAt\":\"later\",\"endedAt\":\"never\"}\n", StandardCharsets.UTF_8);
        Path missing = temporaryDirectory.resolve("missing.jsonl");

        var facts = new RuntimeLayer().analyse(LayerInput.forRuntimeFiles(input, missing));

        assertTrue(facts.edges().isEmpty());
        assertEquals(4, facts.diagnostics().size());
        assertEquals(2, facts.diagnostics().stream().filter(diagnostic ->
                diagnostic.code() == DiagnosticCode.RUNTIME_JSON_INVALID).count());
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.RUNTIME_SCHEMA_UNSUPPORTED));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.RUNTIME_INPUT_NOT_FOUND));
    }

    @Test
    void keepsOnlyLatestAggregateForRepeatedRunDump(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("trigger-and-shutdown.jsonl");
        String prefix = "{\"schema\":\"ghostmap.runtime/v1\",\"runId\":\"same-run\","
                + "\"filter\":{\"include\":[\"sample\"],\"exclude\":[]},"
                + "\"edgeLimit\":10,\"droppedEvents\":0,\"edges\":[{"
                + "\"caller\":{\"owner\":\"sample.Caller\",\"name\":\"call\",\"descriptor\":\"()V\"},"
                + "\"callee\":{\"owner\":\"sample.Target\",\"name\":\"work\",\"descriptor\":\"()V\"},";
        String trigger = prefix + "\"hits\":1,\"dispatch\":\"UNKNOWN\"}],"
                + "\"startedAt\":\"2026-11-22T00:00:00Z\",\"endedAt\":\"2026-11-22T00:00:01Z\","
                + "\"reason\":\"trigger\"}";
        String shutdown = prefix + "\"hits\":4,\"dispatch\":\"UNKNOWN\"}],"
                + "\"startedAt\":\"2026-11-22T00:00:00Z\",\"endedAt\":\"2026-11-22T00:00:02Z\","
                + "\"reason\":\"shutdown\"}";
        Files.writeString(input, trigger + '\n' + shutdown + '\n', StandardCharsets.UTF_8);

        var facts = new RuntimeLayer().analyse(LayerInput.forRuntimeFiles(input));
        RuntimeCallFact call = (RuntimeCallFact) facts.edges().getFirst();

        assertEquals(1, facts.edges().size());
        assertEquals(4, call.hits());
        assertEquals(DispatchKind.UNKNOWN, call.dispatch());
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(RuntimeLayerTest.class.getResource(name).toURI());
    }
}
