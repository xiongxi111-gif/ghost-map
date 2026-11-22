package dev.ghostmap.core.layer.wiring;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringLayerSecurityTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void rejectsExternalEntityRemoteDtdAndEntityExpansionWithoutReadingSecret() throws Exception {
        Path secret = Files.writeString(temporaryDirectory.resolve("secret.txt"), "PLANTED_SECRET_VALUE");
        Path root = Files.createDirectory(temporaryDirectory.resolve("xml"));
        Files.writeString(root.resolve("xxe.xml"), """
                <!DOCTYPE beans [<!ENTITY xxe SYSTEM "%s">]>
                <beans><bean id="&xxe;" class="sample.Bad"/></beans>
                """.formatted(secret.toUri()));
        Files.writeString(root.resolve("remote.xml"), """
                <!DOCTYPE beans SYSTEM "http://127.0.0.1:9/never-requested.dtd">
                <beans><bean id="remote" class="sample.Bad"/></beans>
                """);
        Files.writeString(root.resolve("expansion.xml"), """
                <!DOCTYPE beans [
                  <!ENTITY a "ha">
                  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">
                  <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;">
                ]>
                <beans><bean id="&c;" class="sample.Bad"/></beans>
                """);

        LayerFacts facts = new WiringLayer().analyse(LayerInput.forResourceRoots(root));

        assertEquals(3, facts.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == DiagnosticCode.XML_SECURITY_VIOLATION).count());
        assertTrue(facts.nodes().isEmpty());
        assertTrue(facts.edges().isEmpty());
        assertFalse(facts.toString().contains("PLANTED_SECRET_VALUE"));
        assertEquals("PLANTED_SECRET_VALUE", Files.readString(secret));
    }
}
