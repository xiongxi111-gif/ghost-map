package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringLayerFailureTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void diagnosesMissingAndCyclicDefinitionsWithoutGuessingOrLooping() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("xml"));
        Files.writeString(temporaryDirectory.resolve("outside.xml"),
                "<beans><bean id=\"outside\" class=\"sample.Outside\"/></beans>");
        Files.writeString(root.resolve("a.xml"), "<beans><import resource=\"b.xml\"/></beans>");
        Files.writeString(root.resolve("b.xml"), "<beans><import resource=\"a.xml\"/></beans>");
        Files.writeString(root.resolve("missing-import.xml"),
                "<beans><import resource=\"absent.xml\"/></beans>");
        Files.writeString(root.resolve("traversal.xml"),
                "<beans><import resource=\"../outside.xml\"/></beans>");
        Files.writeString(root.resolve("remote-import.xml"),
                "<beans><import resource=\"https://example.invalid/never.xml\"/></beans>");
        Files.writeString(root.resolve("malformed.xml"), "<beans><bean id=\"broken\"></beans>");
        Files.writeString(root.resolve("problems.xml"), """
                <beans>
                  <bean id="duplicate" class="sample.First"/>
                  <bean id="duplicate" class="sample.Second"/>
                  <bean id="parentA" parent="parentB"/>
                  <bean id="parentB" parent="parentA"/>
                  <bean id="missingParent" parent="absentParent"/>
                  <bean id="dangling" class="sample.Dangling">
                    <property name="missing" ref="absentBean"/>
                  </bean>
                  <alias name="absentAliasTarget" alias="missingAlias"/>
                  <alias name="cycleB" alias="cycleA"/>
                  <alias name="cycleA" alias="cycleB"/>
                </beans>
                """);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forResourceRoots(root));
        Set<DiagnosticCode> codes = facts.diagnostics().stream().map(diagnostic -> diagnostic.code())
                .collect(Collectors.toSet());

        assertTrue(codes.contains(DiagnosticCode.XML_IMPORT_MISSING));
        assertTrue(codes.contains(DiagnosticCode.XML_IMPORT_OUTSIDE_ROOT));
        assertTrue(codes.contains(DiagnosticCode.XML_IMPORT_CYCLE));
        assertTrue(codes.contains(DiagnosticCode.XML_MALFORMED));
        assertTrue(codes.contains(DiagnosticCode.XML_PARENT_MISSING));
        assertTrue(codes.contains(DiagnosticCode.XML_PARENT_CYCLE));
        assertTrue(codes.contains(DiagnosticCode.XML_REFERENCE_MISSING));
        assertTrue(codes.contains(DiagnosticCode.XML_ALIAS_TARGET_MISSING));
        assertTrue(codes.contains(DiagnosticCode.XML_ALIAS_CYCLE));
        assertTrue(codes.contains(DiagnosticCode.XML_DUPLICATE_BEAN));
        assertTrue(facts.nodes().stream().noneMatch(node -> node.id().value().contains("outside")));
        assertTrue(facts.edges().stream().filter(WiringInjectionFact.class::isInstance)
                .map(WiringInjectionFact.class::cast)
                .noneMatch(edge -> edge.from().value().contains("dangling")));
    }

    @Test
    void missingAndNonDirectoryResourceRootsReturnDiagnostics() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        Path file = Files.writeString(temporaryDirectory.resolve("one.xml"), "<beans/>");

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forResourceRoots(missing, file));

        assertEquals(2, facts.diagnostics().size());
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.RESOURCE_ROOT_NOT_FOUND));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.RESOURCE_ROOT_UNSUPPORTED));
    }
}
