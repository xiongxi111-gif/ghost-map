package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.runtime.RuntimeCallFact;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R06RuntimeFloorTest {
    @Test
    void matchingPairConfirmsOneStaticEdgeAndAddsRuntimeFloorEvidence() {
        MethodId caller = method("sample.Caller", "call");
        MethodId callee = method("sample.Target", "work");
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE,
                List.of(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, 1, 3, false, false),
                        JoinTestSupport.methodNode(LayerId.BYTECODE, callee, 5, 7, false, false)),
                List.of(JoinTestSupport.bytecodeCall(caller, callee, 4, OptionalInt.of(2),
                        dev.ghostmap.core.layer.bytecode.JvmInvocationKind.INVOKESTATIC)));
        RuntimeCallFact runtimeCall = JoinTestSupport.runtimeCall(caller, callee, 7, DispatchKind.UNKNOWN);

        var graph = new JoinPolicy().join(bytecode,
                JoinTestSupport.facts(LayerId.RUNTIME, List.of(), List.of(runtimeCall)));
        Edge.Resolved edge = (Edge.Resolved) graph.modelEdges().getFirst();

        assertEquals(EdgeStatus.CONFIRMED, edge.status());
        assertEquals(EdgeKind.CALLS, edge.kind());
        assertEquals(caller, edge.from());
        assertEquals(callee, edge.to());
        assertEquals(7, edge.evidence().stream().filter(dev.ghostmap.model.Evidence.Runtime.class::isInstance)
                .map(dev.ghostmap.model.Evidence.Runtime.class::cast).findFirst().orElseThrow().hits());
        assertEquals(java.util.Set.of(LayerId.BYTECODE, LayerId.RUNTIME), edge.provenance());
        assertTrue(edge.rules().contains(R06RuntimeFloor.ID));
        assertTrue(edge.confidence() >= R06RuntimeFloor.RUNTIME_WEIGHT);
    }

    @Test
    void unmatchedStaticEdgeStaysStaticAndRuntimePairBecomesOnePhantom() {
        MethodId caller = method("sample.Caller", "call");
        MethodId staticTarget = method("sample.StaticTarget", "work");
        MethodId actualTarget = method("sample.ActualTarget", "work");
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE, List.of(), List.of(
                JoinTestSupport.bytecodeCall(caller, staticTarget, 4, OptionalInt.of(2),
                        dev.ghostmap.core.layer.bytecode.JvmInvocationKind.INVOKESTATIC)));
        var runtime = JoinTestSupport.facts(LayerId.RUNTIME, List.of(),
                List.of(JoinTestSupport.runtimeCall(caller, actualTarget, 3, DispatchKind.UNKNOWN)));

        var graph = new JoinPolicy().join(bytecode, runtime);
        List<Edge> edges = graph.modelEdges();
        Edge.Resolved unchanged = edges.stream().filter(edge -> edge.kind() == EdgeKind.CALLS)
                .map(Edge.Resolved.class::cast).findFirst().orElseThrow();
        Edge.Resolved phantom = edges.stream().filter(edge -> edge.kind() == EdgeKind.PHANTOM)
                .map(Edge.Resolved.class::cast).findFirst().orElseThrow();

        assertFalse(unchanged.provenance().contains(LayerId.RUNTIME));
        assertEquals(EdgeStatus.PHANTOM, phantom.status());
        assertEquals(actualTarget, phantom.to());
        assertEquals(1, edges.stream().filter(edge -> edge.provenance().contains(LayerId.RUNTIME)).count());
    }

    private static MethodId method(String owner, String name) {
        return new MethodId(owner, name, "()V");
    }
}
