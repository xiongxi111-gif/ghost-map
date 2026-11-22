package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.EntryId;
import dev.ghostmap.model.CanonicalId.ExternalId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalIdProperties {
    @Property(tries = 1_000)
    @Label("parse(format(id)) preserves every canonical id kind")
    void parserAndFormatterRoundTrip(@ForAll("canonicalIds") CanonicalId id) {
        assertEquals(id, CanonicalIds.parse(CanonicalIds.format(id)));
    }

    @Provide
    Arbitrary<CanonicalId> canonicalIds() {
        return Arbitraries.oneOf(types(), methods(), fields(), beans(), entries(), externals(), sites());
    }

    private Arbitrary<CanonicalId> types() {
        return binaryNames().map(TypeId::new);
    }

    private Arbitrary<CanonicalId> methods() {
        return methodIds().map(id -> id);
    }

    private Arbitrary<MethodId> methodIds() {
        Arbitrary<String> names = Arbitraries.of("run", "findById", "lambda$main$0", "<init>", "access$100");
        return Combinators.combine(binaryNames(), names, methodDescriptors()).as(MethodId::new);
    }

    private Arbitrary<CanonicalId> fields() {
        Arbitrary<String> names = Arbitraries.of("value", "owners", "this$0", "$VALUES");
        return Combinators.combine(binaryNames(), names, fieldDescriptors()).as(FieldId::new)
                .map(id -> id);
    }

    private Arbitrary<CanonicalId> beans() {
        return Combinators.combine(
                Arbitraries.of("annotations", "business-config.xml", "spring/data-context.xml"),
                Arbitraries.of("clinicService", "ownerRepository", "bean-1"))
                .as(BeanId::new).map(id -> id);
    }

    private Arbitrary<CanonicalId> entries() {
        return Combinators.combine(
                Arbitraries.of("http", "main", "scheduled", "listener"),
                Arbitraries.of("GET /owners/{ownerId}", "dev.example.Main", "nightly:cleanup", "orders.created"))
                .as(EntryId::new).map(id -> id);
    }

    private Arbitrary<CanonicalId> externals() {
        return Combinators.combine(
                Arbitraries.of("jdbc", "http", "config"),
                Arbitraries.of("owners", "api.example.org:v1", "feature.owner-cache"))
                .as(ExternalId::new).map(id -> id);
    }

    private Arbitrary<CanonicalId> sites() {
        return Combinators.combine(methodIds(), Arbitraries.integers().between(0, 65_535))
                .as(SiteId::new).map(id -> id);
    }

    private Arbitrary<String> binaryNames() {
        Arbitrary<String> segment = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8);
        Arbitrary<String> suffix = Arbitraries.of("", "$Inner", "$1", "$1Local", "$Outer$2");
        return Combinators.combine(segment.list().ofMinSize(2).ofMaxSize(5), suffix)
                .as((segments, nested) -> String.join(".", segments) + nested);
    }

    private Arbitrary<String> fieldDescriptors() {
        return Arbitraries.of("B", "C", "D", "F", "I", "J", "S", "Z",
                "[B", "[I", "[[D", "[[Z", "Ljava/lang/String;",
                "[Ljava/lang/String;", "Ldev/example/Outer$Inner;");
    }

    private Arbitrary<String> methodDescriptors() {
        Arbitrary<List<String>> parameters = fieldDescriptors().list().ofMaxSize(5);
        Arbitrary<String> result = Arbitraries.oneOf(fieldDescriptors(), Arbitraries.just("V"));
        return Combinators.combine(parameters, result)
                .as((items, returnType) -> "(" + String.join("", items) + ")" + returnType);
    }
}
