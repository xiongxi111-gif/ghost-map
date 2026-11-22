package dev.ghostmap.core.layer.source;

import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.NodeKind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLayerFixtureTest {
    private static LayerFacts facts;

    @BeforeAll
    static void analyseFixture() {
        Path sources = repositoryRoot().resolve("fixtures/haunted-house/src/main/java");
        facts = new SourceLayer().analyse(new LayerInput(sources));
        assertTrue(facts.diagnostics().isEmpty(), () -> "unexpected diagnostics: " + facts.diagnostics());
    }

    @Test
    void emitsLocatedTypeMethodAndFieldDeclarationsWithAnnotations() throws Exception {
        FactNode spirit = node(NodeKind.TYPE, "Spirit");
        FactNode recordSighting = facts.nodes().stream()
                .filter(node -> node.kind() == NodeKind.METHOD && node.name().equals("recordSighting"))
                .filter(node -> node.owner().map(TypeId::binaryName)
                        .filter("dev.ghostmap.fixtures.hauntedhouse.HauntingService"::equals).isPresent())
                .findFirst().orElseThrow();
        FactNode announcer = facts.nodes().stream()
                .filter(node -> node.kind() == NodeKind.FIELD && node.name().equals("announcer"))
                .filter(node -> node.owner().map(TypeId::binaryName)
                        .filter("dev.ghostmap.fixtures.hauntedhouse.HauntingService"::equals).isPresent())
                .findFirst().orElseThrow();

        assertEquals(List.of("Data"), annotations(spirit));
        assertTrue(annotations(recordSighting).contains("Transactional"));
        assertTrue(annotations(announcer).contains("Qualifier"));
        for (FactNode node : List.of(spirit, recordSighting, announcer)) {
            assertFalse(node.location().file().isBlank());
            assertTrue(node.location().line() > 0);
            assertTrue(node.location().endLine() >= node.location().line());
        }

        Path sources = repositoryRoot().resolve("fixtures/haunted-house/src/main/java");
        long javaFileCount;
        try (Stream<Path> paths = Files.walk(sources)) {
            javaFileCount = paths.filter(path -> path.getFileName().toString().endsWith(".java")).count();
        }
        Set<String> filesWithTypes = facts.nodes().stream().filter(node -> node.kind() == NodeKind.TYPE)
                .map(node -> node.location().file()).collect(java.util.stream.Collectors.toSet());
        assertEquals(javaFileCount, filesWithTypes.size());
    }

    @Test
    void emitsFixtureCallsAndBestEffortReceiverTypes() {
        List<SourceCallFact> calls = calls();
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("setName")));
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("getName")));
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("invoke")
                && call.textualReceiver().equals("method")
                && call.receiverType().map(TypeId::binaryName)
                .filter("java.lang.reflect.Method"::equals).isPresent()
                && call.dynamicKind() == DynamicCallKind.REFLECTION));
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("getName")
                && call.textualReceiver().equals("spirit")
                && call.receiverType().map(TypeId::binaryName)
                .filter("dev.ghostmap.fixtures.hauntedhouse.Spirit"::equals).isPresent()));
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("get")
                && call.textualReceiver().equals("handlers")
                && call.dynamicKind() == DynamicCallKind.REGISTRY));

        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("announce")
                && call.receiverType().map(TypeId::binaryName)
                .filter("dev.ghostmap.fixtures.hauntedhouse.ApparitionAnnouncer"::equals).isPresent()
                && call.dispatch() == DispatchKind.INTERFACE));
        assertTrue(calls.stream().allMatch(call -> call.evidence().line() > 0));
    }

    @Test
    void recordsOnlyLiteralArgumentsAtKnownDynamicApis() {
        SourceCallFact constantLookup = calls().stream()
                .filter(call -> call.methodName().equals("getBean"))
                .filter(call -> call.stringLiterals().contains("ancestralEcho"))
                .findFirst().orElseThrow();
        SourceCallFact variableLookup = calls().stream()
                .filter(call -> call.methodName().equals("getBean"))
                .filter(call -> call.evidence().text().contains("getBean(beanName)"))
                .findFirst().orElseThrow();

        assertEquals(DynamicCallKind.GET_BEAN, constantLookup.dynamicKind());
        assertEquals("org.springframework.context.ApplicationContext",
                constantLookup.receiverType().orElseThrow().binaryName());
        assertEquals(List.of("ancestralEcho"), constantLookup.stringLiterals());
        assertEquals(DynamicCallKind.GET_BEAN, variableLookup.dynamicKind());
        assertTrue(variableLookup.stringLiterals().isEmpty());
    }

    private static FactNode node(NodeKind kind, String name) {
        return facts.nodes().stream()
                .filter(node -> node.kind() == kind && node.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static List<SourceCallFact> calls() {
        return facts.edges().stream().map(SourceCallFact.class::cast).toList();
    }

    private static List<String> annotations(FactNode node) {
        JsonValue value = node.attrs().get("annotations");
        if (!(value instanceof ArrayValue array)) {
            throw new AssertionError("annotations attribute is not an array");
        }
        return array.values().stream().map(item -> ((StringValue) item).value()).toList();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("fixtures/haunted-house/pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("cannot locate repository root");
        }
        return current;
    }
}
