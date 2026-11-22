package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.FactNode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.StringValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringLayerFixtureTest {
    @Test
    void hauntedHouseChildInheritsClassAndUnresolvedAnnotationBeanReference() {
        Path resources = repositoryRoot().resolve("fixtures/haunted-house/src/main/resources");

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forResourceRoots(resources));

        FactNode child = facts.nodes().stream()
                .filter(node -> node.id().value().equals("b:haunted-house-context.xml#ancestralEcho"))
                .findFirst().orElseThrow();
        assertEquals("dev.ghostmap.fixtures.hauntedhouse.EchoChamber",
                ((StringValue) child.attrs().get("class")).value());
        assertEquals(List.of("b:haunted-house-context.xml#echoTemplate"),
                strings(child, "inheritanceChain"));
        assertTrue(strings(child, "references").contains("PROPERTY:announcer->rattlingAnnouncer"));
        assertTrue(strings(child, "properties").contains("PROPERTY:prefix=ancestral:"));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.XML_REFERENCE_MISSING
                        && diagnostic.message().contains("rattlingAnnouncer")));
        assertFalse(facts.edges().stream().filter(WiringBindFact.class::isInstance)
                .anyMatch(edge -> edge.from().value().equals("b:haunted-house-context.xml#echoTemplate")));
        assertTrue(facts.edges().stream().filter(WiringBindFact.class::isInstance)
                .map(WiringBindFact.class::cast)
                .anyMatch(edge -> edge.from().value().equals("b:haunted-house-context.xml#ancestralEcho")
                        && edge.target().value().equals("t:dev.ghostmap.fixtures.hauntedhouse.EchoChamber")));
    }

    private static List<String> strings(FactNode node, String name) {
        ArrayValue array = (ArrayValue) node.attrs().get(name);
        return array.values().stream().map(value -> ((StringValue) value).value()).toList();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("fixtures/haunted-house/pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("cannot locate repository root");
        }
        return current;
    }
}
