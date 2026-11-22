package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.BytecodeCallFact;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.bytecode.BytecodeLayer;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R07DynamicSitesTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void asmConstantsResolveKnownKeysWhileVariableAndReflectiveSitesRemainUnresolved() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("dynamic"), Map.of(
                "org.springframework.context.ApplicationContext", """
                        package org.springframework.context;
                        public interface ApplicationContext {
                            Object getBean(String name);
                            Object getBean(String name, Class<?> type);
                        }
                        """,
                "sample.DynamicCalls", """
                        package sample;
                        import java.lang.invoke.MethodHandle;
                        import java.lang.reflect.Method;
                        import java.util.Map;
                        import org.springframework.context.ApplicationContext;
                        class DynamicCalls {
                            ApplicationContext context;
                            Map<String,Object> handlers;
                            Object known() { return context.getBean("knownBean", Object.class); }
                            Object variable(String name) { return context.getBean(name); }
                            Object registry() { return handlers.get("knownBean"); }
                            Object reflect(Method method, Object target) throws Exception { return method.invoke(target); }
                            Class<?> load() throws Exception { return Class.forName("sample.Loaded"); }
                            Object handle(MethodHandle handle) throws Throwable { return handle.invokeExact(); }
                        }
                        """), true);
        var bytecode = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));
        BeanId bean = new BeanId("annotations", "knownBean");
        FactNode beanNode = beanNode(bean, List.of());
        var wiring = JoinTestSupport.facts(LayerId.WIRING, List.of(beanNode),
                List.of(JoinTestSupport.wiringBind(bean, new TypeId("sample.KnownBean"))));

        var graph = new JoinPolicy().join(bytecode, wiring);
        List<BytecodeCallFact> calls = bytecode.edges().stream().filter(BytecodeCallFact.class::isInstance)
                .map(BytecodeCallFact.class::cast).filter(call -> call.dynamicKind() != BytecodeDynamicKind.NONE)
                .toList();

        assertTrue(calls.stream().filter(call -> call.methodName().equals("getBean"))
                .anyMatch(call -> call.constantStringArgument().equals(Optional.of("knownBean"))));
        assertTrue(calls.stream().filter(call -> call.methodName().equals("getBean"))
                .anyMatch(call -> call.constantStringArgument().isEmpty()));
        assertEquals(calls.size(), graph.dynamicEdges().size());
        assertTrue(graph.dynamicEdges().stream().filter(Edge.Resolved.class::isInstance)
                .map(Edge.Resolved.class::cast).anyMatch(edge -> edge.to().equals(bean)));
        assertTrue(graph.dynamicEdges().stream().filter(Edge.Resolved.class::isInstance)
                .map(Edge.Resolved.class::cast).anyMatch(edge ->
                        edge.to().equals(new TypeId("sample.Loaded"))));
        assertTrue(graph.dynamicEdges().stream().filter(Edge.Unresolved.class::isInstance)
                .map(Edge.Unresolved.class::cast).anyMatch(edge -> edge.dispatch() == dev.ghostmap.model.DispatchKind.REFLECTION));
        assertTrue(graph.dynamicEdges().stream().filter(Edge.Unresolved.class::isInstance)
                .map(Edge.Unresolved.class::cast).anyMatch(edge -> edge.partialKey().isEmpty()));
    }

    @Test
    void ambiguousConstantBeanNameStaysUnresolvedWithBothCandidates() {
        MethodId caller = new MethodId("sample.Caller", "call", "()V");
        MethodId getBean = new MethodId("org.springframework.context.ApplicationContext", "getBean",
                "(Ljava/lang/String;)Ljava/lang/Object;");
        var dynamic = JoinTestSupport.dynamicBytecodeCall(caller, getBean, 7, 10,
                dev.ghostmap.core.layer.bytecode.JvmInvocationKind.INVOKEINTERFACE,
                BytecodeDynamicKind.GET_BEAN, Optional.of("shared"));
        BeanId first = new BeanId("annotations", "first");
        BeanId second = new BeanId("annotations", "second");
        var wiring = JoinTestSupport.facts(LayerId.WIRING,
                List.of(beanNode(first, List.of("shared")), beanNode(second, List.of("shared"))),
                List.of(JoinTestSupport.wiringBind(first, new TypeId("sample.First")),
                        JoinTestSupport.wiringBind(second, new TypeId("sample.Second"))));

        var graph = new JoinPolicy().join(
                JoinTestSupport.facts(LayerId.BYTECODE,
                        List.of(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, 9, 11, false, false)),
                        List.of(dynamic)), wiring);
        Edge.Unresolved unresolved = (Edge.Unresolved) graph.dynamicEdges().get(0);

        assertEquals(List.of(first, second), unresolved.candidates());
        assertEquals(Optional.of("shared"), unresolved.partialKey());
    }

    private static FactNode beanNode(BeanId bean, List<String> aliases) {
        return new FactNode(JoinTestSupport.canonical(bean), NodeKind.BEAN, bean.beanId(), Optional.empty(),
                Map.of("aliases", new ArrayValue(aliases.stream()
                        .map(alias -> (dev.ghostmap.model.JsonValue) new dev.ghostmap.model.JsonValue.StringValue(alias))
                        .toList()), "generated", new BooleanValue(false)),
                new SourceLocation("wiring.xml", 3, 3));
    }
}
