package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.Confidence;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R04VirtualExpansionProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void virtualWeightSplitsAcrossExactlyKCandidatesForVariedMethodsAndEvidence(
            @ForAll @IntRange(min = 1, max = 12) int implementationCount,
            @ForAll("methodNames") String methodName,
            @ForAll @IntRange(min = 0, max = 4) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 5, max = 400) int callLine,
            @ForAll boolean includeAst,
            @ForAll boolean receiverResolved,
            @ForAll long shuffleSeed) {
        TypeId contract = new TypeId("sample.Contract" + Math.floorMod((int) shuffleSeed, 50));
        String descriptor = JoinTestSupport.methodDescriptor(arity, descriptorFlavor);
        MethodId contractMethod = new MethodId(contract.binaryName(), methodName, descriptor);
        TypeId callerType = new TypeId("sample.Caller" + Math.floorMod((int) (shuffleSeed >>> 8), 50));
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        List<FactNode> nodes = new ArrayList<>();
        nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, contract, true));
        nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, contractMethod, 1, 1, true, false));
        nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, callerType, false));
        nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, callLine - 1, callLine + 1, false, false));
        List<EdgeFact> edgeFacts = new ArrayList<>();
        edgeFacts.add(JoinTestSupport.bytecodeCall(caller, contractMethod, 4, OptionalInt.of(callLine),
                JvmInvocationKind.INVOKEINTERFACE));
        for (int index = 0; index < implementationCount; index++) {
            TypeId implementation = new TypeId("sample.Impl" + index + "For" + contract.binaryName()
                    .substring(contract.binaryName().lastIndexOf('.') + 1));
            MethodId implementationMethod = new MethodId(implementation.binaryName(), methodName, descriptor);
            nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, implementation, false));
            nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, implementationMethod,
                    20 + index, 20 + index, false, false));
            edgeFacts.add(JoinTestSupport.implementsEdge(implementation, contract));
        }
        var bytes = JoinTestSupport.facts(LayerId.BYTECODE,
                JoinTestSupport.shuffled(nodes, shuffleSeed), JoinTestSupport.shuffled(edgeFacts, ~shuffleSeed));
        SourceCallFact sourceCall = JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), callLine,
                "receiver", methodName, arity, receiverResolved ? Optional.of(contract) : Optional.empty());
        var ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.methodNode(LayerId.AST, caller, callLine - 1, callLine + 1,
                        false, false)), List.of(sourceCall));

        var graph = includeAst
                ? ((shuffleSeed & 1L) == 0 ? new JoinPolicy().join(ast, bytes) : new JoinPolicy().join(bytes, ast))
                : new JoinPolicy().join(bytes);

        assertEquals(implementationCount, graph.edges().size());
        double candidateWeight = R04VirtualExpansion.VIRTUAL_WEIGHT / implementationCount;
        double expectedConfidence = includeAst
                ? Confidence.noisyOr(receiverResolved ? 0.55 : 0.30, candidateWeight)
                : candidateWeight;
        for (JoinedEdge edge : graph.edges()) {
            assertEquals(EdgeStatus.CANDIDATE, edge.status());
            assertEquals(implementationCount, edge.candidates().size());
            double bytecodeWeight = edge.contributions().stream()
                    .filter(contribution -> contribution.layer() == LayerId.BYTECODE)
                    .findFirst().orElseThrow().weight();
            assertEquals(candidateWeight, bytecodeWeight);
            assertEquals(expectedConfidence, edge.confidence());
            assertTrue(edge.confidence() >= candidateWeight);
            assertTrue(edge.confidence() >= 0.0 && edge.confidence() < 1.0);
            assertTrue(edge.rules().contains("R04"));
        }
        if (implementationCount == 1 && !includeAst) {
            assertEquals(0.85, graph.edges().get(0).confidence());
        }
    }

    @Provide
    Arbitrary<String> methodNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }
}
