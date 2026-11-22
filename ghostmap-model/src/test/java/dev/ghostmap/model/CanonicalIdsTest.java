package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.BeanId;
import dev.ghostmap.model.CanonicalId.EntryId;
import dev.ghostmap.model.CanonicalId.ExternalId;
import dev.ghostmap.model.CanonicalId.FieldId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.CanonicalId.TypeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalIdsTest {
    @Test
    void formatsEveryDesignTableKind() {
        TypeId type = new TypeId("dev.example.Outer$1");
        MethodId method = new MethodId(type.binaryName(), "accept", "([I[[Ljava/lang/String;)V");

        assertEquals("t:dev.example.Outer$1", type.value());
        assertEquals("m:dev.example.Outer$1#accept([I[[Ljava/lang/String;)V", method.value());
        assertEquals("f:dev.example.Outer$1#values:[I",
                new FieldId(type.binaryName(), "values", "[I").value());
        assertEquals("b:annotations#ownerRepository", new BeanId("annotations", "ownerRepository").value());
        assertEquals("e:http:GET /owners/{ownerId}",
                new EntryId("http", "GET /owners/{ownerId}").value());
        assertEquals("x:http:api.example.org", new ExternalId("http", "api.example.org").value());
        assertEquals("s:" + method.value() + "@42", new SiteId(method, 42).value());
    }

    @Test
    void rejectsMalformedDescriptors() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldId("dev.example.Owner", "owners", "java.lang.String"));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodId("dev.example.Owner", "find", "(V)V"));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodId("dev.example.Owner", "find", "([V)V"));
    }
}
