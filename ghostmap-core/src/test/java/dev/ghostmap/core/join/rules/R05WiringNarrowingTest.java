package dev.ghostmap.core.join.rules;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.BytecodeLayer;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import dev.ghostmap.core.layer.bytecode.JvmInvocationKind;
import dev.ghostmap.core.layer.wiring.InjectionKind;
import dev.ghostmap.core.layer.wiring.UnresolvedWiringFact;
import dev.ghostmap.core.layer.wiring.WiringInjectionPoint;
import dev.ghostmap.core.layer.wiring.WiringLayer;
import dev.ghostmap.core.layer.source.SourceLayer;
import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.EdgeStatus;
import dev.ghostmap.model.LayerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R05WiringNarrowingTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void inheritedXmlInjectionNarrowsInterfaceCallToChildBeanImplementation() throws Exception {
        Path workspace = temporaryDirectory.resolve("xml-parent-narrowing");
        Path classes = TestCompiler.compile(workspace, Map.of("sample.Echo", """
                package sample;
                interface Announcer { String announce(); }
                class Rattle implements Announcer { public String announce() { return "rattle"; } }
                class Whisper implements Announcer { public String announce() { return "whisper"; } }
                public class Echo {
                    Announcer announcer;
                    public void setAnnouncer(Announcer announcer) { this.announcer = announcer; }
                    String echo() { return announcer.announce(); }
                }
                """), true);
        Path resources = Files.createDirectory(workspace.resolve("resources"));
        Files.writeString(resources.resolve("wiring.xml"), """
                <beans>
                  <bean id="template" class="sample.Echo" abstract="true">
                    <property name="announcer" ref="rattle"/>
                  </bean>
                  <bean id="echo" parent="template"/>
                  <bean id="rattle" class="sample.Rattle"/>
                  <bean id="whisper" class="sample.Whisper"/>
                </beans>
                """);
        var source = new SourceLayer().analyse(new LayerInput(workspace.resolve("src")));
        var bytecode = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));
        var wiring = new WiringLayer().analyse(
                new LayerInput(Optional.empty(), List.of(classes), List.of(resources)));

        var graph = new JoinPolicy().join(source, bytecode, wiring);
        List<dev.ghostmap.core.join.JoinedEdge> calls = graph.edges().stream()
                .filter(edge -> edge.from().value().startsWith("m:sample.Echo#echo"))
                .filter(edge -> edge.target().map(target -> target.value().contains("#announce"))
                        .orElse(false)).toList();

        assertEquals(1, calls.size());
        assertEquals("m:sample.Rattle#announce()Ljava/lang/String;",
                calls.get(0).target().orElseThrow().value());
        assertEquals(EdgeStatus.PREDICTED, calls.get(0).status());
        assertTrue(calls.get(0).rules().contains("R05"));
        assertTrue(calls.get(0).provenance().contains(LayerId.WIRING));
    }

    @Test
    void unresolvedWiringLeavesBothVirtualCandidatesWithoutInventingWinner() {
        TypeId contract = new TypeId("sample.Contract");
        TypeId first = new TypeId("sample.First");
        TypeId second = new TypeId("sample.Second");
        TypeId callerType = new TypeId("sample.Caller");
        MethodId contractMethod = new MethodId(contract.binaryName(), "run", "()V");
        MethodId caller = new MethodId(callerType.binaryName(), "call", "()V");
        List<FactNode> bytecodeNodes = new ArrayList<>();
        bytecodeNodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, contract, true));
        bytecodeNodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, contractMethod, 1, 1, true, false));
        bytecodeNodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE, caller, 10, 12, false, false));
        for (TypeId implementation : List.of(first, second)) {
            bytecodeNodes.add(JoinTestSupport.typeNode(LayerId.BYTECODE, implementation, false));
            bytecodeNodes.add(JoinTestSupport.methodNode(LayerId.BYTECODE,
                    new MethodId(implementation.binaryName(), "run", "()V"), 20, 20, false, false));
        }
        List<EdgeFact> bytecodeEdges = List.of(
                JoinTestSupport.bytecodeCall(caller, contractMethod, 4, OptionalInt.of(11),
                        JvmInvocationKind.INVOKEINTERFACE),
                JoinTestSupport.implementsEdge(first, contract),
                JoinTestSupport.implementsEdge(second, contract));
        BeanId callerBean = new BeanId("annotations", "caller");
        BeanId firstBean = new BeanId("annotations", "first");
        BeanId secondBean = new BeanId("annotations", "second");
        UnresolvedWiringFact unresolved = new UnresolvedWiringFact(JoinTestSupport.canonical(callerBean),
                new WiringInjectionPoint(InjectionKind.PROPERTY, Optional.of("handler"), OptionalInt.empty()),
                contract.value(), Optional.empty(), List.of(firstBean, secondBean), "Sample.class", 11);

        var graph = new JoinPolicy().join(
                JoinTestSupport.facts(LayerId.AST,
                        List.of(JoinTestSupport.methodNode(LayerId.AST, caller, 10, 12, false, false)),
                        List.of(JoinTestSupport.sourceCall(JoinTestSupport.canonical(caller), 11,
                                "handler", "run", 0, Optional.of(contract)))),
                JoinTestSupport.facts(LayerId.BYTECODE, bytecodeNodes, bytecodeEdges),
                JoinTestSupport.facts(LayerId.WIRING, List.of(), List.of(
                        JoinTestSupport.wiringBind(callerBean, callerType),
                        JoinTestSupport.wiringBind(firstBean, first),
                        JoinTestSupport.wiringBind(secondBean, second), unresolved)));

        assertEquals(2, graph.edges().size());
        assertEquals(List.of(EdgeStatus.CANDIDATE, EdgeStatus.CANDIDATE),
                graph.edges().stream().map(edge -> edge.status()).toList());
        assertFalse(graph.edges().stream().anyMatch(edge -> edge.rules().contains("R05")));
    }
}
