package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedGraph;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.ProvisionalId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R01IdentityProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void canonicalMergeIsIndependentOfLayerAndFactOrder(
            @ForAll("methodNames") String methodName,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 1, max = 400) int line,
            @ForAll @IntRange(min = 0, max = 50) int ownerIndex,
            @ForAll long shuffleSeed) {
        TypeId owner = new TypeId("sample.Ghost" + ownerIndex);
        MethodId method = new MethodId(owner.binaryName(), methodName,
                JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
        FactNode source = JoinTestSupport.methodNode(LayerId.AST, method, line, line + 2, false, false);
        FactNode bytecode = JoinTestSupport.methodNode(LayerId.BYTECODE, method, line, line + 2, false, false);
        var ast = JoinTestSupport.facts(LayerId.AST, JoinTestSupport.shuffled(List.of(
                source, JoinTestSupport.typeNode(LayerId.AST, owner, false)), shuffleSeed), List.of());
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, JoinTestSupport.shuffled(List.of(
                bytecode, JoinTestSupport.typeNode(LayerId.BYTECODE, owner, false)), ~shuffleSeed), List.of());

        boolean reverseLayers = (shuffleSeed & 1L) != 0;
        JoinedGraph graph = reverseLayers ? new JoinPolicy().join(bytes, ast) : new JoinPolicy().join(ast, bytes);
        JoinedGraph opposite = reverseLayers ? new JoinPolicy().join(ast, bytes) : new JoinPolicy().join(bytes, ast);
        var mergedMethod = graph.nodes().stream().filter(node -> node.id().equals(method)).findFirst().orElseThrow();

        assertEquals(opposite, graph);
        assertEquals(1, graph.nodes().stream().filter(node -> node.id().equals(method)).count());
        assertEquals(List.of("AST", "BYTECODE"),
                JoinTestSupport.strings(mergedMethod.attrs().get("provenance")));
    }

    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void provisionalMethodReconcilesByOwnerNameArityAndOverlappingRange(
            @ForAll("methodNames") String methodName,
            @ForAll @IntRange(min = 0, max = 6) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 1, max = 400) int startLine,
            @ForAll @IntRange(min = 1, max = 20) int sourceSpan,
            @ForAll @IntRange(min = 0, max = 100) int overlapSelector,
            @ForAll @IntRange(min = 0, max = 50) int ownerIndex,
            @ForAll long shuffleSeed) {
        TypeId owner = new TypeId("sample.Ghost" + ownerIndex);
        MethodId canonical = new MethodId(owner.binaryName(), methodName,
                JoinTestSupport.methodDescriptor(arity, descriptorFlavor));
        int endLine = startLine + sourceSpan;
        int bytecodeLine = startLine + Math.floorMod(overlapSelector, sourceSpan + 1);
        ProvisionalId provisional = new ProvisionalId(NodeKind.METHOD, owner, methodName, arity,
                startLine, endLine);
        FactNode source = new FactNode(provisional, NodeKind.METHOD, methodName, Optional.of(owner),
                Map.of("annotations", new ArrayValue(List.of()), "generated", new BooleanValue(false)),
                new SourceLocation("Ghost.java", startLine, endLine));
        FactNode bytecode = JoinTestSupport.methodNode(LayerId.BYTECODE, canonical,
                bytecodeLine, bytecodeLine, false, false);
        var ast = JoinTestSupport.facts(LayerId.AST, List.of(source), List.of());
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE, List.of(bytecode), List.of());

        JoinedGraph graph = (shuffleSeed & 1L) == 0
                ? new JoinPolicy().join(ast, bytes) : new JoinPolicy().join(bytes, ast);

        assertTrue(graph.unresolvedNodes().isEmpty());
        assertEquals(1, graph.nodes().stream().filter(node -> node.id().equals(canonical)).count());
    }

    @Provide
    Arbitrary<String> methodNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }
}
