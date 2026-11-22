package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.TestCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationWiringUnresolvedTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void emitsCandidateListsForAmbiguousPrimaryTiesMissingTypesAndDanglingResources() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory, Map.ofEntries(
                Map.entry("sample.Port", "package sample; public interface Port {}"),
                Map.entry("sample.A", component("A", "a", "Port", false)),
                Map.entry("sample.B", component("B", "b", "Port", false)),
                Map.entry("sample.PrimaryPort", "package sample; public interface PrimaryPort {}"),
                Map.entry("sample.P1", component("P1", "p1", "PrimaryPort", true)),
                Map.entry("sample.P2", component("P2", "p2", "PrimaryPort", true)),
                Map.entry("sample.Missing", "package sample; public interface Missing {}"),
                Map.entry("sample.AmbiguousConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.stereotype.Component;
                        @Component public class AmbiguousConsumer { @Autowired Port port; }
                        """),
                Map.entry("sample.PrimaryTieConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.stereotype.Component;
                        @Component public class PrimaryTieConsumer { @Autowired PrimaryPort port; }
                        """),
                Map.entry("sample.MissingConsumer", """
                        package sample;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.stereotype.Component;
                        @Component public class MissingConsumer { @Autowired Missing missing; }
                        """),
                Map.entry("sample.DanglingResourceConsumer", """
                        package sample;
                        import jakarta.annotation.Resource;
                        import org.springframework.stereotype.Component;
                        @Component public class DanglingResourceConsumer {
                            @Resource(name="absent") Port port;
                        }
                        """)), true);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forClassRoots(classes));

        assertCandidates(facts, "ambiguousConsumer", List.of("b:annotations#a", "b:annotations#b"));
        assertCandidates(facts, "primaryTieConsumer", List.of("b:annotations#p1", "b:annotations#p2"));
        assertCandidates(facts, "missingConsumer", List.of());
        assertCandidates(facts, "danglingResourceConsumer", List.of());
        assertTrue(facts.diagnostics().stream().filter(diagnostic ->
                diagnostic.code() == DiagnosticCode.WIRING_UNRESOLVED_INJECTION).count() >= 4);
        assertTrue(facts.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .noneMatch(edge -> edge.from().value().contains("Consumer")));
    }

    private static void assertCandidates(LayerFacts facts, String owner, List<String> expected) {
        UnresolvedWiringFact unresolved = facts.edges().stream().filter(UnresolvedWiringFact.class::isInstance)
                .map(UnresolvedWiringFact.class::cast)
                .filter(edge -> edge.from().value().equals("b:annotations#" + owner))
                .findFirst().orElseThrow();
        assertEquals(expected, unresolved.candidates().stream().map(Object::toString).toList());
    }

    private static String component(String className, String beanName, String interfaceName, boolean primary) {
        return "package sample;\n"
                + "import org.springframework.stereotype.Service;\n"
                + (primary ? "import org.springframework.context.annotation.Primary;\n@Primary\n" : "")
                + "@Service(\"" + beanName + "\") public class " + className
                + " implements " + interfaceName + " {}\n";
    }
}
