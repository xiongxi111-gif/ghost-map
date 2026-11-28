package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R08PhantomsTest {
    @Test
    void phantomLinksOnlyToSameCallerUnresolvedSiteWithMatchingDispatch() {
        MethodId caller = method("sample.Caller", "call");
        MethodId reflectionApi = new MethodId("java.lang.reflect.Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
        MethodId reflected = method("sample.Hidden", "run");
        MethodId lambdaTarget = method("sample.LambdaTarget", "run");
        var laterDynamic = JoinTestSupport.dynamicBytecodeCall(caller, reflectionApi, 19, 3,
                JvmInvocationKind.INVOKEVIRTUAL, BytecodeDynamicKind.REFLECTION, Optional.empty());
        var nearerDynamic = JoinTestSupport.dynamicBytecodeCall(caller, reflectionApi, 5, 2,
                JvmInvocationKind.INVOKEVIRTUAL, BytecodeDynamicKind.REFLECTION, Optional.empty());
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE, List.of(), List.of(laterDynamic, nearerDynamic));
        var runtime = JoinTestSupport.facts(LayerId.RUNTIME, List.of(), List.of(
                JoinTestSupport.runtimeCall(caller, reflected, 2, DispatchKind.REFLECTION),
                JoinTestSupport.runtimeCall(caller, lambdaTarget, 1, DispatchKind.LAMBDA)));

        var graph = new JoinPolicy().join(bytecode, runtime);
        List<Edge.Resolved> phantoms = graph.modelEdges().stream()
                .filter(edge -> edge.kind() == EdgeKind.PHANTOM).map(Edge.Resolved.class::cast).toList();
        Edge.Resolved reflection = phantoms.stream().filter(edge -> edge.to().equals(reflected))
                .findFirst().orElseThrow();
        Edge.Resolved lambda = phantoms.stream().filter(edge -> edge.to().equals(lambdaTarget))
                .findFirst().orElseThrow();

        assertEquals(2, phantoms.size());
        assertEquals(nearerDynamic.site(), reflection.site().orElseThrow());
        assertTrue(lambda.site().isEmpty());
        assertEquals(DispatchKind.REFLECTION, reflection.dispatch());
        assertEquals(List.of(R08Phantoms.ID), reflection.rules());
    }

    @Test
    void runtimePairResolvedByR07IsConfirmedAndNeverDuplicatedAsPhantom() {
        MethodId caller = method("sample.Loader", "load");
        MethodId classForName = new MethodId("java.lang.Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;");
        MethodId loadedInitializer = new MethodId("sample.Loaded", "<clinit>", "()V");
        var dynamic = JoinTestSupport.dynamicBytecodeCall(caller, classForName, 8, 2,
                JvmInvocationKind.INVOKESTATIC, BytecodeDynamicKind.CLASS_LOADING,
                Optional.of("sample.Loaded"));
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE, List.of(), List.of(dynamic));
        var runtime = JoinTestSupport.facts(LayerId.RUNTIME, List.of(), List.of(
                JoinTestSupport.runtimeCall(caller, loadedInitializer, 1, DispatchKind.DYNAMIC)));

        var graph = new JoinPolicy().join(bytecode, runtime);
        List<Edge> runtimeEdges = graph.modelEdges().stream()
                .filter(edge -> edge.provenance().contains(LayerId.RUNTIME)).toList();
        Edge.Resolved confirmed = (Edge.Resolved) runtimeEdges.getFirst();

        assertEquals(1, runtimeEdges.size());
        assertEquals(EdgeKind.CALLS, confirmed.kind());
        assertEquals(EdgeStatus.CONFIRMED, confirmed.status());
        assertEquals(loadedInitializer, confirmed.to());
        assertTrue(confirmed.rules().contains(R06RuntimeFloor.ID));
        assertTrue(confirmed.rules().contains(R07DynamicSites.ID));
        assertFalse(graph.modelEdges().stream().anyMatch(edge -> edge.kind() == EdgeKind.PHANTOM));
    }

    private static MethodId method(String owner, String name) {
        return new MethodId(owner, name, "()V");
    }
}
