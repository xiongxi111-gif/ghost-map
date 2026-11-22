package dev.ghostmap.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Dependency-free JSON values used for extensible node attributes. */
public sealed interface JsonValue permits JsonValue.StringValue, JsonValue.NumberValue, JsonValue.BooleanValue,
        JsonValue.ArrayValue, JsonValue.ObjectValue {

    record StringValue(String value) implements JsonValue {
        public StringValue {
            if (value == null) {
                throw new NullPointerException("value");
            }
        }
    }

    record NumberValue(BigDecimal value) implements JsonValue {
        public NumberValue {
            if (value == null) {
                throw new NullPointerException("value");
            }
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }
    }

    record BooleanValue(boolean value) implements JsonValue {
    }

    record ArrayValue(List<JsonValue> values) implements JsonValue {
        public ArrayValue {
            values = ModelChecks.immutableList(values, "values");
        }
    }

    record ObjectValue(Map<String, JsonValue> values) implements JsonValue {
        public ObjectValue {
            values = ModelChecks.immutableMap(values, "values");
            values.keySet().forEach(key -> ModelChecks.nonBlank(key, "attribute key"));
        }
    }
}
