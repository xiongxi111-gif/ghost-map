package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.JsonValue.ArrayValue;
import dev.ghostmap.model.JsonValue.StringValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRecordsTest {
    @Test
    void constructorsTakeImmutableCopies() {
        List<JsonValue> values = new ArrayList<>(List.of(new StringValue("one")));
        ArrayValue array = new ArrayValue(values);
        values.add(new StringValue("two"));

        Map<String, JsonValue> attrs = new HashMap<>();
        attrs.put("items", array);
        Node node = new Node(new TypeId("dev.example.Owner"), NodeKind.TYPE, "Owner",
                Optional.empty(), attrs, Optional.empty());
        attrs.clear();

        assertEquals(1, array.values().size());
        assertEquals(1, node.attrs().size());
        assertThrows(UnsupportedOperationException.class,
                () -> node.attrs().put("other", new StringValue("value")));
    }

    @Test
    void recordsRejectNullInsteadOfUsingItAsMissingData() {
        assertThrows(NullPointerException.class,
                () -> new Node(new TypeId("dev.example.Owner"), NodeKind.TYPE, "Owner",
                        null, Map.of(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RuntimeRun("run-1", "tests", null));
    }
}
