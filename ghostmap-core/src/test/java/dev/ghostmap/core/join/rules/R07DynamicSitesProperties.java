package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.NodeKind;
import dev.ghostmap.model.SourceLocation;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R07DynamicSitesProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void everyDetectedDynamicSiteBecomesResolvedOrUnresolved(
            @ForAll("dynamicKinds") BytecodeDynamicKind dynamicKind,
            @ForAll("keys") String generatedKey,
            @ForAll boolean constantPresent,
            @ForAll boolean keyMatches,
            @ForAll @IntRange(min = 1, max = 500) int line,
            @ForAll @IntRange(min = 1, max = 500) int offset,
            @ForAll long shuffleSeed) {
        MethodId caller = new MethodId("sample.Caller" + Math.floorMod((int) shuffleSeed, 100),
                "call", "()V");
        MethodId dynamicTarget = target(dynamicKind);
        String key = dynamicKind == BytecodeDynamicKind.CLASS_LOADING
                ? "sample." + Character.toUpperCase(generatedKey.charAt(0)) + generatedKey.substring(1)
                : keyMatches ? "knownBean" : "missing" + generatedKey;
        var bytecodeCall = JoinTestSupport.dynamicBytecodeCall(caller, dynamicTarget, offset, line,
                invocation(dynamicKind), dynamicKind, constantPresent ? Optional.of(key) : Optional.empty());
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE,
                List.of(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, line, line, false, false)),
                List.of(bytecodeCall));
        BeanId bean = new BeanId("annotations", "knownBean");
        TypeId beanType = new TypeId("sample.KnownBean");
        FactNode beanNode = new FactNode(JoinTestSupport.canonical(bean), NodeKind.BEAN, "knownBean",
                Optional.empty(), Map.of("aliases", new ArrayValue(List.of()),
                "generated", new BooleanValue(false)), new SourceLocation("wiring.xml", 3, 3));
        var wiring = JoinTestSupport.facts(LayerId.WIRING, List.of(beanNode),
                List.of(JoinTestSupport.wiringBind(bean, beanType)));

        var graph = new JoinPolicy().join(JoinTestSupport.shuffled(List.of(bytecode, wiring), shuffleSeed));

        assertEquals(1, graph.dynamicEdges().size());
        Edge edge = graph.dynamicEdges().get(0);
        boolean shouldResolve = constantPresent && (dynamicKind == BytecodeDynamicKind.CLASS_LOADING
                || keyMatches && (dynamicKind == BytecodeDynamicKind.GET_BEAN
                || dynamicKind == BytecodeDynamicKind.REGISTRY));
        assertEquals(shouldResolve, edge instanceof Edge.Resolved);
        assertEquals(!shouldResolve, edge instanceof Edge.Unresolved);
        assertEquals(bytecodeCall.site(), edge.from());
        assertTrue(edge.rules().contains("R07"));
        assertTrue(edge.confidence() >= 0.0 && edge.confidence() < 1.0);
        assertTrue(graph.nodes().stream().anyMatch(node -> node.id().equals(bytecodeCall.site())));
        if (edge instanceof Edge.Resolved resolved) {
            assertEquals(EdgeStatus.PREDICTED, resolved.status());
            if (dynamicKind == BytecodeDynamicKind.GET_BEAN || dynamicKind == BytecodeDynamicKind.REGISTRY) {
                assertEquals(Set.of(LayerId.BYTECODE, LayerId.WIRING), resolved.provenance());
                assertEquals(bean, resolved.to());
            }
        } else if (edge instanceof Edge.Unresolved unresolved) {
            assertEquals(constantPresent ? Optional.of(key) : Optional.empty(), unresolved.partialKey());
        }
    }

    @Provide
    Arbitrary<BytecodeDynamicKind> dynamicKinds() {
        return Arbitraries.of(BytecodeDynamicKind.GET_BEAN, BytecodeDynamicKind.REFLECTION,
                BytecodeDynamicKind.CLASS_LOADING, BytecodeDynamicKind.METHOD_HANDLE,
                BytecodeDynamicKind.REGISTRY);
    }

    @Provide
    Arbitrary<String> keys() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
    }

    private static MethodId target(BytecodeDynamicKind kind) {
        return switch (kind) {
            case GET_BEAN -> new MethodId("org.springframework.context.ApplicationContext", "getBean",
                    "(Ljava/lang/String;)Ljava/lang/Object;");
            case REFLECTION -> new MethodId("java.lang.reflect.Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
            case CLASS_LOADING -> new MethodId("java.lang.Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
            case METHOD_HANDLE -> new MethodId("java.lang.invoke.MethodHandle", "invokeExact",
                    "()Ljava/lang/Object;");
            case REGISTRY -> new MethodId("java.util.Map", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;");
            case NONE, INVOKEDYNAMIC -> throw new IllegalArgumentException("not a dynamic API kind");
        };
    }

    private static JvmInvocationKind invocation(BytecodeDynamicKind kind) {
        return kind == BytecodeDynamicKind.CLASS_LOADING ? JvmInvocationKind.INVOKESTATIC
                : kind == BytecodeDynamicKind.REFLECTION || kind == BytecodeDynamicKind.METHOD_HANDLE
                ? JvmInvocationKind.INVOKEVIRTUAL : JvmInvocationKind.INVOKEINTERFACE;
    }
}
