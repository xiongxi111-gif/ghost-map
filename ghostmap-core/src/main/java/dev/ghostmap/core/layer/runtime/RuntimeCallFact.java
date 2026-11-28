package dev.ghostmap.core.layer.runtime;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.MethodId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** One observed caller-to-callee pair from a probe run. */
public record RuntimeCallFact(SymbolId.Canonical from, SymbolId.Canonical target, String runId, long hits,
                              DispatchKind dispatch, Instant startedAt, Instant endedAt,
                              List<String> filter, Path source) implements EdgeFact {
    public RuntimeCallFact {
        if (from == null || target == null || dispatch == null || startedAt == null || endedAt == null
                || filter == null || source == null) {
            throw new NullPointerException("runtime call fields must not be null");
        }
        if (!(from.id() instanceof MethodId) || !(target.id() instanceof MethodId)) {
            throw new IllegalArgumentException("runtime call endpoints must be canonical method ids");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (hits < 1) {
            throw new IllegalArgumentException("hits must be positive");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt must not precede startedAt");
        }
        filter = List.copyOf(filter);
        filter.forEach(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("runtime filter values must not be blank");
            }
        });
        source = source.toAbsolutePath().normalize();
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.CALLS;
    }

    public MethodId caller() {
        return (MethodId) from.id();
    }

    public MethodId callee() {
        return (MethodId) target.id();
    }

    public Evidence.Runtime evidence() {
        return new Evidence.Runtime(runId, hits);
    }
}
