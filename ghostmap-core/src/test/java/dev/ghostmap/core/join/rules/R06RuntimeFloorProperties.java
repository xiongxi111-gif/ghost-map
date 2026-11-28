package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R06RuntimeFloorProperties {
    @Property(tries = 250)
    void runtimeEvidenceConfirmsAndNeverLowersConfidence(@ForAll @IntRange(min = 1, max = 10_000) int hits,
                                                         @ForAll boolean virtual) {
        MethodId caller = new MethodId("sample.Caller", "call", "()V");
        MethodId callee = new MethodId("sample.Target", "work", "()V");
        JvmInvocationKind invocation = virtual ? JvmInvocationKind.INVOKEVIRTUAL : JvmInvocationKind.INVOKESTATIC;
        var bytecode = JoinTestSupport.facts(LayerId.BYTECODE, List.of(), List.of(
                JoinTestSupport.bytecodeCall(caller, callee, 3, OptionalInt.of(2), invocation)));
        double before = ((Edge.Resolved) new JoinPolicy().join(bytecode).modelEdges().getFirst()).confidence();
        var runtime = JoinTestSupport.facts(LayerId.RUNTIME, List.of(), List.of(
                JoinTestSupport.runtimeCall(caller, callee, hits, DispatchKind.UNKNOWN)));

        Edge.Resolved after = (Edge.Resolved) new JoinPolicy().join(bytecode, runtime).modelEdges().getFirst();

        assertEquals(EdgeStatus.CONFIRMED, after.status());
        assertTrue(after.confidence() >= before);
        assertTrue(after.confidence() >= R06RuntimeFloor.RUNTIME_WEIGHT);
        assertTrue(after.rules().contains(R06RuntimeFloor.ID));
    }
}
