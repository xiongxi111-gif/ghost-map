package dev.ghostmap.core.layer.wiring;

import java.util.Optional;
import java.util.OptionalInt;

/** Property or constructor slot associated with an XML injection edge. */
public record WiringInjectionPoint(InjectionKind kind, Optional<String> name, OptionalInt index) {
    public WiringInjectionPoint {
        if (kind == null || name == null || index == null) {
            throw new NullPointerException("injection point fields must not be null");
        }
        name.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("injection point name must not be blank");
            }
        });
        if (index.isPresent() && index.orElseThrow() < 0) {
            throw new IllegalArgumentException("constructor index must not be negative");
        }
    }
}
