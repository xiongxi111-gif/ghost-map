package dev.ghostmap.fixtures.hauntedhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundTruthValidationTest {
    private static final Set<String> REQUIRED_CAUSES = Set.of(
            "LOMBOK",
            "QUALIFIED_INTERFACE_DISPATCH",
            "XML_PARENT",
            "CONSTANT_GETBEAN",
            "VARIABLE_GETBEAN",
            "REFLECTION",
            "REGISTRY",
            "TRANSACTIONAL_PROXY",
            "JDBC");
    private static final Set<String> EDGE_KINDS = Set.of("CALLS", "INJECTS", "BINDS", "ACCESSES", "UNRESOLVED");
    private static final Set<String> DISPATCH_KINDS = Set.of(
            "STATIC", "VIRTUAL", "INTERFACE", "DYNAMIC", "REFLECTION", "PROXY", "REGISTRY");

    @Test
    void groundTruthMatchesFixtureSourcesAndCanonicalIdentityRules() throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("fixture.basedir")).toAbsolutePath().normalize();
        JsonNode root = new ObjectMapper().readTree(fixtureRoot.resolve("ground-truth.json").toFile());

        assertEquals("ghostmap.ground-truth/v1", requiredText(root, "schema"));
        validateCanonical(requiredText(required(root, "workload"), "entry"));
        assertEquals(REQUIRED_CAUSES, textSet(required(root, "requiredCauses")));

        JsonNode edges = required(root, "edges");
        assertTrue(edges.isArray());
        assertFalse(edges.isEmpty());

        Set<String> labels = new HashSet<>();
        Set<String> seenCauses = new HashSet<>();
        boolean sawVariableUnresolved = false;
        for (JsonNode edge : edges) {
            String label = requiredText(edge, "label");
            assertTrue(labels.add(label), "duplicate edge label: " + label);
            String kind = requiredText(edge, "kind");
            assertTrue(EDGE_KINDS.contains(kind), "unknown edge kind for " + label);
            String dispatch = requiredText(edge, "dispatch");
            assertTrue(DISPATCH_KINDS.contains(dispatch), "unknown dispatch kind for " + label);
            String cause = requiredText(edge, "cause");
            assertTrue(REQUIRED_CAUSES.contains(cause), "unknown cause for " + label);
            seenCauses.add(cause);

            assertCanonical(requiredText(edge, "from"), label + ".from");
            JsonNode target = required(edge, "to");
            if ("UNRESOLVED".equals(kind)) {
                assertTrue(target.isNull(), label + " unresolved target must be null");
            } else {
                assertFalse(target.isNull(), label + " resolved target must be present");
                assertCanonical(target.textValue(), label + ".to");
            }
            if (edge.has("site")) {
                assertCanonical(requiredText(edge, "site"), label + ".site");
            }
            if (edge.has("receiverField")) {
                assertCanonical(requiredText(edge, "receiverField"), label + ".receiverField");
            }
            if (edge.has("candidates")) {
                for (JsonNode candidate : required(edge, "candidates")) {
                    assertCanonical(candidate.textValue(), label + ".candidate");
                }
            }

            validateLocation(fixtureRoot, required(edge, "location"), label);
            JsonNode observability = required(edge, "observability");
            assertTrue(required(observability, "astExpectedToMiss").isBoolean());
            assertTrue(required(observability, "runtimeProbeExpected").isBoolean());
            assertFalse(requiredText(observability, "reason").isBlank());

            if ("VARIABLE_GETBEAN".equals(cause)) {
                sawVariableUnresolved = true;
                assertEquals("UNRESOLVED", kind);
                assertEquals("DYNAMIC", dispatch);
                assertTrue(requiredText(edge, "from").startsWith("s:"));
            }
        }

        assertEquals(REQUIRED_CAUSES, seenCauses);
        assertTrue(sawVariableUnresolved, "variable getBean must remain explicit and unresolved");
    }

    private static void validateLocation(Path fixtureRoot, JsonNode location, String label) throws IOException {
        String relativeFile = requiredText(location, "file");
        Path source = fixtureRoot.resolve(relativeFile).normalize();
        assertTrue(source.startsWith(fixtureRoot), label + " location escapes the fixture");
        assertTrue(Files.isRegularFile(source), label + " source file does not exist: " + relativeFile);

        int line = required(location, "line").intValue();
        assertTrue(line > 0, label + " line must be positive");
        java.util.List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        assertTrue(line <= lines.size(), label + " line is outside " + relativeFile);
        String anchor = requiredText(location, "contains");
        assertTrue(lines.get(line - 1).contains(anchor), label + " line no longer contains: " + anchor);
    }

    private static Set<String> textSet(JsonNode array) {
        assertTrue(array.isArray());
        Set<String> result = new HashSet<>();
        for (JsonNode item : array) {
            assertTrue(item.isTextual());
            result.add(item.textValue());
        }
        return Set.copyOf(result);
    }

    private static JsonNode required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        assertNotNull(value, "missing field: " + field);
        return value;
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = required(object, field);
        assertTrue(value.isTextual(), field + " must be text");
        return value.textValue();
    }

    private static void assertCanonical(String id, String field) {
        assertNotNull(id, field + " must be text");
        assertDoesNotThrow(() -> validateCanonical(id), field + " is not canonical: " + id);
    }

    private static void validateCanonical(String id) {
        if (id == null || id.length() < 3 || id.charAt(1) != ':') {
            throw invalidId(id);
        }
        switch (id.charAt(0)) {
            case 't' -> requireBinaryName(id.substring(2));
            case 'm' -> validateMethodId(id);
            case 'f' -> validateFieldId(id);
            case 'b' -> validatePair(id.substring(2), '#');
            case 'e', 'x' -> validatePair(id.substring(2), ':');
            case 's' -> validateSiteId(id);
            default -> throw invalidId(id);
        }
    }

    private static void validateMethodId(String id) {
        int hash = id.indexOf('#', 2);
        int descriptorStart = id.indexOf('(', hash + 1);
        if (hash < 3 || descriptorStart <= hash + 1) {
            throw invalidId(id);
        }
        requireBinaryName(id.substring(2, hash));
        requireNonBlank(id.substring(hash + 1, descriptorStart), id);
        validateMethodDescriptor(id.substring(descriptorStart));
    }

    private static void validateFieldId(String id) {
        int hash = id.indexOf('#', 2);
        int descriptorStart = id.indexOf(':', hash + 1);
        if (hash < 3 || descriptorStart <= hash + 1) {
            throw invalidId(id);
        }
        requireBinaryName(id.substring(2, hash));
        requireNonBlank(id.substring(hash + 1, descriptorStart), id);
        int end = parseType(id.substring(descriptorStart + 1), 0);
        if (end != id.length() - descriptorStart - 1) {
            throw invalidId(id);
        }
    }

    private static void validateSiteId(String id) {
        int at = id.lastIndexOf('@');
        if (at < 4 || at == id.length() - 1) {
            throw invalidId(id);
        }
        String methodId = id.substring(2, at);
        if (!methodId.startsWith("m:")) {
            throw invalidId(id);
        }
        validateMethodId(methodId);
        int offset = Integer.parseInt(id.substring(at + 1));
        if (offset < 0) {
            throw invalidId(id);
        }
    }

    private static void validateMethodDescriptor(String descriptor) {
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            throw invalidId(descriptor);
        }
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            index = parseType(descriptor, index);
        }
        if (index >= descriptor.length()) {
            throw invalidId(descriptor);
        }
        index++;
        int end = index < descriptor.length() && descriptor.charAt(index) == 'V'
                ? index + 1 : parseType(descriptor, index);
        if (end != descriptor.length()) {
            throw invalidId(descriptor);
        }
    }

    private static int parseType(String descriptor, int start) {
        if (start >= descriptor.length()) {
            throw invalidId(descriptor);
        }
        char symbol = descriptor.charAt(start);
        if ("BCDFIJSZ".indexOf(symbol) >= 0) {
            return start + 1;
        }
        if (symbol == '[') {
            return parseType(descriptor, start + 1);
        }
        if (symbol == 'L') {
            int semicolon = descriptor.indexOf(';', start + 1);
            if (semicolon <= start + 1) {
                throw invalidId(descriptor);
            }
            return semicolon + 1;
        }
        throw invalidId(descriptor);
    }

    private static void validatePair(String value, char separator) {
        int index = value.indexOf(separator);
        if (index < 1 || index == value.length() - 1) {
            throw invalidId(value);
        }
    }

    private static void requireBinaryName(String value) {
        requireNonBlank(value, value);
        if (value.indexOf('/') >= 0 || value.indexOf('#') >= 0 || value.indexOf(':') >= 0
                || value.indexOf(';') >= 0 || value.indexOf('[') >= 0) {
            throw invalidId(value);
        }
    }

    private static void requireNonBlank(String value, String id) {
        if (value.isBlank()) {
            throw invalidId(id);
        }
    }

    private static IllegalArgumentException invalidId(String id) {
        return new IllegalArgumentException("invalid canonical id: " + id);
    }
}
