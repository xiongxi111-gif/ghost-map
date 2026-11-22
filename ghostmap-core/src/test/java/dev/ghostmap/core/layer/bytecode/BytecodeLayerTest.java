package dev.ghostmap.core.layer.bytecode;

import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.JsonValue;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.JsonValue.StringValue;
import dev.ghostmap.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeLayerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void emitsDescriptorMembersAllInvokeKindsHierarchyAnnotationsAndGeneratedMembers() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("opcodes"), Map.of(
                "sample.OpcodeShowcase", opcodeSource()), true);

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));

        assertTrue(facts.diagnostics().isEmpty(), () -> "unexpected diagnostics: " + facts.diagnostics());
        FactNode nested = node(facts, "t:sample.OpcodeShowcase$Nested");
        assertEquals("Nested", nested.name());
        assertTrue(facts.nodes().stream().anyMatch(node ->
                node.id().value().equals("f:sample.OpcodeShowcase#numbers:[I")));
        assertTrue(facts.nodes().stream().anyMatch(node ->
                node.id().value().equals("f:sample.OpcodeShowcase$Nested#matrix:[[J")));
        FactNode mix = node(facts, "m:sample.OpcodeShowcase#mix([IJ)I");
        assertTrue(annotations(mix).contains("sample.Mark"));
        assertTrue(annotations(node(facts, "t:sample.OpcodeShowcase")).contains("sample.Mark"));
        assertTrue(annotations(node(facts, "f:sample.OpcodeShowcase#numbers:[I")).contains("sample.Mark"));

        Set<JvmInvocationKind> kinds = EnumSet.noneOf(JvmInvocationKind.class);
        List<BytecodeCallFact> calls = calls(facts);
        calls.forEach(call -> kinds.add(call.invocationKind()));
        assertEquals(EnumSet.allOf(JvmInvocationKind.class), kinds);
        Set<JvmInvocationKind> mixKinds = EnumSet.noneOf(JvmInvocationKind.class);
        calls.stream().filter(call -> call.from().value().equals(mix.id().value()))
                .forEach(call -> mixKinds.add(call.invocationKind()));
        assertEquals(EnumSet.allOf(JvmInvocationKind.class), mixKinds);
        assertTrue(calls.stream().filter(call -> call.from().value().equals(mix.id().value()))
                .allMatch(call -> call.sourceLine().isPresent()));
        assertTrue(calls.stream().allMatch(call -> call.site().bytecodeOffset() >= 0));
        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("staticTarget")
                && call.owner().equals("sample/OpcodeShowcase")
                && call.descriptor().equals("()V")
                && call.target().map(target -> target.value().equals(
                        "m:sample.OpcodeShowcase#staticTarget()V")).orElse(false)));

        assertTrue(facts.edges().stream().filter(BytecodeHierarchyFact.class::isInstance)
                .map(BytecodeHierarchyFact.class::cast)
                .anyMatch(edge -> edge.kind() == EdgeKind.EXTENDS
                        && edge.from().value().equals("t:sample.OpcodeShowcase")
                        && edge.target().value().equals("t:sample.Parent")));
        assertTrue(facts.edges().stream().filter(BytecodeHierarchyFact.class::isInstance)
                .map(BytecodeHierarchyFact.class::cast)
                .anyMatch(edge -> edge.kind() == EdgeKind.IMPLEMENTS
                        && edge.target().value().equals("t:sample.Contract")));

        FactNode bridge = node(facts, "m:sample.OpcodeShowcase#value()Ljava/lang/Object;");
        assertTrue(generated(bridge));
        assertTrue(strings(bridge, "generationReasons").contains("BRIDGE"));
        assertTrue(generated(node(facts, "m:sample.Coordinates#x()I")));
    }

    @Test
    void detectsDynamicDispatchSitesFromInstructions() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("dynamic"), Map.of(
                "org.springframework.context.ApplicationContext", """
                        package org.springframework.context;
                        public interface ApplicationContext {
                            Object getBean(String name);
                        }
                        """,
                "sample.DynamicSites", """
                        package sample;
                        import java.lang.invoke.MethodHandle;
                        import java.lang.reflect.Method;
                        import java.util.Map;
                        import org.springframework.context.ApplicationContext;

                        class DynamicSites {
                            ApplicationContext context;
                            Map<String, Runnable> handlers;

                            Object bean() { return context.getBean("known"); }
                            Object reflect(Method method, Object target) throws Exception {
                                return method.invoke(target);
                            }
                            Class<?> load(String name) throws Exception { return Class.forName(name); }
                            Object handle(MethodHandle handle) throws Throwable { return handle.invokeExact(); }
                            Runnable registry(String key) { return handlers.get(key); }
                        }
                        """), true);

        List<BytecodeCallFact> calls = calls(new BytecodeLayer().analyse(LayerInput.forClassRoots(classes)));

        assertTrue(calls.stream().anyMatch(call -> call.methodName().equals("getBean")
                && call.dynamicKind() == BytecodeDynamicKind.GET_BEAN));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals("java/lang/reflect/Method")
                && call.methodName().equals("invoke")
                && call.dynamicKind() == BytecodeDynamicKind.REFLECTION));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals("java/lang/Class")
                && call.methodName().equals("forName")
                && call.dynamicKind() == BytecodeDynamicKind.CLASS_LOADING));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals("java/lang/invoke/MethodHandle")
                && call.methodName().equals("invokeExact")
                && call.dynamicKind() == BytecodeDynamicKind.METHOD_HANDLE));
        assertTrue(calls.stream().anyMatch(call -> call.owner().equals("java/util/Map")
                && call.methodName().equals("get")
                && call.dynamicKind() == BytecodeDynamicKind.REGISTRY));
    }

    @Test
    void marksLombokGeneratedDataAndRequiredConstructorMembers() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("lombok"), Map.of(
                "sample.GhostData", """
                        package sample;
                        import lombok.Data;
                        import lombok.RequiredArgsConstructor;

                        @Data
                        class GhostData {
                            private String name;
                        }

                        @RequiredArgsConstructor
                        class GhostService {
                            private final GhostData data;
                            String name() { return data.getName(); }
                        }
                        """), true);

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));

        for (String id : List.of(
                "m:sample.GhostData#getName()Ljava/lang/String;",
                "m:sample.GhostData#setName(Ljava/lang/String;)V",
                "m:sample.GhostService#<init>(Lsample/GhostData;)V")) {
            FactNode generated = node(facts, id);
            assertTrue(generated(generated), id);
            assertTrue(strings(generated, "generationReasons").contains("GENERATED_ANNOTATION"), id);
        }
    }

    @Test
    void representsMissingLineTablesWithoutCrashing() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("nodebug"), Map.of(
                "sample.NoDebug", """
                        package sample;
                        class NoDebug {
                            long now() { return System.nanoTime(); }
                        }
                        """), false);

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));
        BytecodeCallFact call = calls(facts).stream().filter(item -> item.methodName().equals("nanoTime"))
                .findFirst().orElseThrow();

        assertTrue(call.sourceLine().isEmpty());
        assertTrue(node(facts, "m:sample.NoDebug#now()J").sourceLocation().isEmpty());
    }

    private static FactNode node(LayerFacts facts, String id) {
        return facts.nodes().stream().filter(node -> node.id().value().equals(id)).findFirst().orElseThrow();
    }

    private static List<BytecodeCallFact> calls(LayerFacts facts) {
        return facts.edges().stream().filter(BytecodeCallFact.class::isInstance)
                .map(BytecodeCallFact.class::cast).toList();
    }

    private static boolean generated(FactNode node) {
        return ((BooleanValue) node.attrs().get("generated")).value();
    }

    private static List<String> annotations(FactNode node) {
        return strings(node, "annotations");
    }

    private static List<String> strings(FactNode node, String attribute) {
        JsonValue value = node.attrs().get(attribute);
        if (!(value instanceof ArrayValue array)) {
            throw new AssertionError(attribute + " is not an array");
        }
        return array.values().stream().map(item -> ((StringValue) item).value()).toList();
    }

    private static String opcodeSource() {
        return """
                package sample;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                @interface Mark {}

                interface Contract { void run(); }
                class Parent<T> { T value() { return null; } }
                class Helper {}
                record Coordinates(int x) {}

                @Mark
                public class OpcodeShowcase extends Parent<String> implements Contract {
                    @Mark int[] numbers;

                    @Mark
                    public int mix(int[] values, long extra) {
                        staticTarget();
                        virtualTarget();
                        Contract contract = this;
                        contract.run();
                        new Helper();
                        Runnable callback = () -> virtualTarget();
                        callback.run();
                        return values.length + (int) extra;
                    }

                    static void staticTarget() {}
                    void virtualTarget() {}
                    @Override public void run() {}
                    @Override public String value() { return "ghost"; }

                    static class Nested { long[][] matrix; }
                }
                """;
    }
}
