package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedEdge;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.source.SourceCallFact;
import dev.ghostmap.model.CanonicalId.BeanId;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R05WiringNarrowingProperties {
    @Property(tries = 250, generation = GenerationMode.RANDOMIZED)
    void resolvedWiringSelectsExactlyOneCandidateWithoutLoweringConfidence(
            @ForAll @IntRange(min = 1, max = 10) int implementationCount,
            @ForAll @IntRange(min = 0, max = 100) int selectedIndex,
            @ForAll("memberNames") String methodName,
            @ForAll("memberNames") String receiverName,
            @ForAll @IntRange(min = 0, max = 4) int arity,
            @ForAll @IntRange(min = 0, max = 5) int descriptorFlavor,
            @ForAll @IntRange(min = 5, max = 400) int callLine,
            @ForAll long shuffleSeed) {
        TypeId contract = new TypeId("sample.Contract" + Math.floorMod((int) shuffleSeed, 50));
        String descriptor = JoinTestSupport.methodDescriptor(arity, descriptorFlavor);
        MethodId contractMethod = new MethodId(contract.binaryName(), methodName, descriptor);
        TypeId callerType = new TypeId("sample.Caller" + Math.floorMod((int) (shuffleSeed >>> 8), 50));
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        BeanId callerBean = new BeanId("annotations", "caller" + Math.floorMod((int) shuffleSeed, 50));
        List<FactNode> nodes = new ArrayList<>();
        nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, contract, true));
        nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, contractMethod, 1, 1, true, false));
        nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, callerType, false));
        nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, callLine - 1, callLine + 1,
                false, false));
        List<EdgeFact> bytecodeEdges = new ArrayList<>();
        bytecodeEdges.add(JoinTestSupport.bytecodeCall(caller, contractMethod, 4,
                OptionalInt.of(callLine), JvmInvocationKind.INVOKEINTERFACE));
        List<TypeId> implementations = new ArrayList<>();
        for (int index = 0; index < implementationCount; index++) {
            TypeId implementation = new TypeId("sample.Impl" + index + "For" + contract.binaryName()
                    .substring(contract.binaryName().lastIndexOf('.') + 1));
            implementations.add(implementation);
            MethodId implementationMethod = new MethodId(implementation.binaryName(), methodName, descriptor);
            nodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, implementation, false));
            nodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, implementationMethod,
                    20 + index, 20 + index, false, false));
            bytecodeEdges.add(JoinTestSupport.implementsEdge(implementation, contract));
        }
        int selected = Math.floorMod(selectedIndex, implementationCount);
        TypeId selectedType = implementations.get(selected);
        BeanId selectedBean = new BeanId("annotations", "selected" + selected);
        LayerFacts ast = JoinTestSupport.facts(LayerId.AST,
                List.of(JoinTestSupport.methodNode(LayerId.AST, caller, callLine - 1, callLine + 1,
                        false, false)),
                List.of(JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), callLine,
                        receiverName, methodName, arity, Optional.of(contract))));
        LayerFacts bytes = JoinTestSupport.facts(LayerId.BYTECODE,
                JoinTestSupport.shuffled(nodes, shuffleSeed),
                JoinTestSupport.shuffled(bytecodeEdges, ~shuffleSeed));
        LayerFacts wiring = JoinTestSupport.facts(LayerId.WIRING, List.of(), List.of(
                JoinTestSupport.wiringBind(callerBean, callerType),
                JoinTestSupport.wiringBind(selectedBean, selectedType),
                JoinTestSupport.wiringInjection(callerBean, selectedBean, receiverName)));

        var graph = new JoinPolicy().join(JoinTestSupport.shuffled(List.of(ast, bytes, wiring), shuffleSeed));
        JoinedEdge edge = graph.edges().get(0);
        double staticConfidence = Confidence.noisyOr(0.55, 0.85 / implementationCount);
        double expected = Confidence.noisyOr(0.55, 0.85 / implementationCount, 0.80);

        assertEquals(1, graph.edges().size());
        assertEquals("m:" + selectedType.binaryName() + '#' + methodName + descriptor,
                edge.target().orElseThrow().value());
        assertEquals(EdgeStatus.PREDICTED, edge.status());
        assertEquals(Set.of(LayerId.AST, LayerId.BYTECODE, LayerId.WIRING), edge.provenance());
        assertEquals(expected, edge.confidence());
        assertTrue(edge.confidence() >= staticConfidence);
        assertTrue(edge.rules().contains("R05"));
        assertEquals(List.of(edge.target().orElseThrow()), edge.candidates());
    }

    @Provide
    Arbitrary<String> memberNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }
}
