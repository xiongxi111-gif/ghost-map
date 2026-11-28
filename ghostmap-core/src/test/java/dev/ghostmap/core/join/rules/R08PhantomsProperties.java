package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.bytecode.BytecodeDynamicKind;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.runtime.RuntimePair;
import dev.ghostmap.model.CanonicalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.Edge;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R08PhantomsProperties {
    @Property(tries = 250)
    void everyRuntimePairBecomesExactlyOneConfirmedOrPhantomEdge(
            @ForAll @IntRange(min = 1, max = 24) int pairCount,
            @ForAll long selection,
            @ForAll boolean resolveFirstAfterR06) {
        List<EdgeFact> bytecodeEdges = new ArrayList<>();
        List<EdgeFact> runtimeEdges = new ArrayList<>();
        List<RuntimePair> pairs = new ArrayList<>();
        for (int index = 0; index < pairCount; index++) {
            MethodId caller = new MethodId("sample.Caller" + index, "call", "()V");
            MethodId callee = new MethodId("sample.Target" + index, "work", "()V");
            DispatchKind dispatch = DispatchKind.UNKNOWN;
            if (index == 0 && resolveFirstAfterR06) {
                MethodId classForName = new MethodId("java.lang.Class", "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;");
                callee = new MethodId("sample.Loaded", "<clinit>", "()V");
                bytecodeEdges.add(JoinTestSupport.dynamicBytecodeCall(caller, classForName, 7, 2,
                        JvmInvocationKind.INVOKESTATIC, BytecodeDynamicKind.CLASS_LOADING,
                        Optional.of("sample.Loaded")));
                dispatch = DispatchKind.DYNAMIC;
            } else if (((selection >>> (index & 63)) & 1L) != 0) {
                bytecodeEdges.add(JoinTestSupport.bytecodeCall(caller, callee, index + 1,
                        OptionalInt.of(index + 1), JvmInvocationKind.INVOKESTATIC));
            }
            runtimeEdges.add(JoinTestSupport.runtimeCall(caller, callee, index + 1L, dispatch));
            pairs.add(new RuntimePair(caller, callee));
        }

        var graph = new JoinPolicy().join(
                JoinTestSupport.facts(LayerId.BYTECODE, List.of(), bytecodeEdges),
                JoinTestSupport.facts(LayerId.RUNTIME, List.of(), runtimeEdges));
        List<Edge> observed = graph.modelEdges().stream()
                .filter(edge -> edge.provenance().contains(LayerId.RUNTIME)).toList();

        assertEquals(pairCount, observed.size());
        for (RuntimePair pair : pairs) {
            List<Edge> represented = observed.stream().filter(edge -> caller(edge).equals(pair.caller()))
                    .filter(edge -> edge.target().filter(pair.callee()::equals).isPresent()).toList();
            assertEquals(1, represented.size(), () -> "runtime pair was not conserved: " + pair);
            assertTrue(represented.getFirst().status() == EdgeStatus.CONFIRMED
                    || represented.getFirst().status() == EdgeStatus.PHANTOM);
            assertTrue(represented.getFirst().kind() == EdgeKind.CALLS
                    || represented.getFirst().kind() == EdgeKind.PHANTOM);
        }
    }

    private static CanonicalId caller(Edge edge) {
        return edge.from() instanceof SiteId site ? site.method() : edge.from();
    }
}
