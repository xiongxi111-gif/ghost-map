package dev.ghostmap.core.layer.source;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLayerFailureTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void parseErrorKeepsPartialDeclarationsAndDoesNotDropSiblingFiles() throws URISyntaxException {
        Path sourceRoot = Path.of(SourceLayerFailureTest.class.getResource("/source-errors").toURI());

        LayerFacts facts = new SourceLayer().analyse(new LayerInput(sourceRoot));

        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.PARSE_ERROR && diagnostic.file().equals("00Broken.java")));
        assertTrue(facts.nodes().stream().anyMatch(node -> node.kind() == NodeKind.TYPE
                && node.name().equals("Broken")));
        assertTrue(facts.nodes().stream().anyMatch(node -> node.kind() == NodeKind.FIELD
                && node.name().equals("preserved")));
        assertTrue(facts.nodes().stream().anyMatch(node -> node.kind() == NodeKind.TYPE
                && node.name().equals("Sibling")));
        assertTrue(facts.edges().stream().map(SourceCallFact.class::cast)
                .anyMatch(call -> call.methodName().equals("strip")));
    }

    @Test
    void missingEmptyAndNonJavaRootsReturnFactsInsteadOfThrowing() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        LayerFacts missingFacts = new SourceLayer().analyse(new LayerInput(missing));
        assertTrue(missingFacts.nodes().isEmpty());
        assertEquals(DiagnosticCode.SOURCE_ROOT_NOT_FOUND, missingFacts.diagnostics().get(0).code());

        Path empty = Files.createDirectory(temporaryDirectory.resolve("empty"));
        LayerFacts emptyFacts = new SourceLayer().analyse(new LayerInput(empty));
        assertTrue(emptyFacts.nodes().isEmpty());
        assertTrue(emptyFacts.diagnostics().isEmpty());

        Files.writeString(empty.resolve("notes.txt"), "not Java");
        LayerFacts nonJavaFacts = new SourceLayer().analyse(new LayerInput(empty));
        assertTrue(nonJavaFacts.nodes().isEmpty());
        assertTrue(nonJavaFacts.diagnostics().isEmpty());

        Path file = Files.writeString(temporaryDirectory.resolve("single.java"), "class Single {}");
        LayerFacts fileFacts = new SourceLayer().analyse(new LayerInput(file));
        assertEquals(DiagnosticCode.SOURCE_ROOT_NOT_DIRECTORY, fileFacts.diagnostics().get(0).code());
    }

    @Test
    void unresolvedSourceTypesStayProvisionalAndUnknownReceiversStayUnknown() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("generic"));
        Files.writeString(root.resolve("GenericBox.java"), """
                package sample;

                @interface Mark {}

                @Mark
                class GenericBox<T> {
                    @Deprecated T value;

                    @Mark
                    T echo(T input) {
                        mystery.run();
                        return input;
                    }
                }
                """);

        LayerFacts facts = new SourceLayer().analyse(new LayerInput(root));
        FactNode generic = findNode(facts, NodeKind.TYPE, "GenericBox");
        FactNode value = findNode(facts, NodeKind.FIELD, "value");
        FactNode echo = findNode(facts, NodeKind.METHOD, "echo");
        SourceCallFact unknownCall = facts.edges().stream().map(SourceCallFact.class::cast)
                .filter(call -> call.methodName().equals("run")).findFirst().orElseThrow();

        assertTrue(annotations(generic).contains("Mark"));
        assertTrue(annotations(value).contains("Deprecated"));
        assertTrue(annotations(echo).contains("Mark"));
        assertInstanceOf(ProvisionalId.class, value.id());
        ProvisionalId provisional = assertInstanceOf(ProvisionalId.class, echo.id());
        assertEquals("p:m:sample.GenericBox#echo/1@9-13", provisional.value());
        assertTrue(unknownCall.receiverType().isEmpty());
    }

    @Test
    void multilineInvocationUsesMethodNameLineAsCallEvidence() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("multiline"));
        Files.writeString(root.resolve("Multiline.java"), """
                package sample;
                class Multiline {
                    String clean() {
                        return "ghost"
                                .strip();
                    }
                }
                """);

        SourceCallFact call = new SourceLayer().analyse(new LayerInput(root)).edges().stream()
                .map(SourceCallFact.class::cast).filter(candidate -> candidate.methodName().equals("strip"))
                .findFirst().orElseThrow();

        assertEquals(5, call.evidence().line());
        assertEquals(4, call.site().location().line());
    }

    @Test
    void memberTypesAndArrayDescriptorsUseJvmBinaryIdentity() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("nested"));
        Files.writeString(root.resolve("Outer.java"), """
                package sample;
                class Outer {
                    class Inner {
                        int[] echo(String[] values) { return new int[values.length]; }
                    }
                }
                """);

        LayerFacts facts = new SourceLayer().analyse(new LayerInput(root));
        FactNode inner = findNode(facts, NodeKind.TYPE, "Inner");
        FactNode echo = findNode(facts, NodeKind.METHOD, "echo");

        assertEquals("t:sample.Outer$Inner", inner.id().value());
        assertEquals("m:sample.Outer$Inner#echo([Ljava/lang/String;)[I", echo.id().value());
        assertFalse(inner.id().provisional());
        assertFalse(echo.id().provisional());
    }

    @Test
    void registryLiteralIsCapturedButOrdinaryStringArgumentsAreNot() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("registry"));
        Files.writeString(root.resolve("Registry.java"), """
                package sample;
                import java.util.Map;

                class Registry {
                    Map<String, Runnable> handlers;
                    void ring() {
                        handlers.get("bell");
                        System.out.println("ordinary");
                    }
                }
                """);

        List<SourceCallFact> calls = new SourceLayer().analyse(new LayerInput(root)).edges().stream()
                .map(SourceCallFact.class::cast).toList();
        SourceCallFact registry = calls.stream().filter(call -> call.methodName().equals("get"))
                .findFirst().orElseThrow();
        SourceCallFact ordinary = calls.stream().filter(call -> call.methodName().equals("println"))
                .findFirst().orElseThrow();

        assertEquals(DynamicCallKind.REGISTRY, registry.dynamicKind());
        assertEquals(List.of("bell"), registry.stringLiterals());
        assertEquals(new TypeId("java.util.Map"), registry.receiverType().orElseThrow());
        assertEquals(DynamicCallKind.NONE, ordinary.dynamicKind());
        assertTrue(ordinary.stringLiterals().isEmpty());
    }

    private static FactNode findNode(LayerFacts facts, NodeKind kind, String name) {
        return facts.nodes().stream().filter(node -> node.kind() == kind && node.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static List<String> annotations(FactNode node) {
        ArrayValue values = assertInstanceOf(ArrayValue.class, node.attrs().get("annotations"));
        return values.values().stream().map(value -> ((StringValue) value).value()).toList();
    }
}
