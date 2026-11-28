package dev.ghostmap.core.layer.runtime;

import dev.ghostmap.model.CanonicalId.MethodId;

/** Canonical identity of one distinct observed runtime method pair. */
public record RuntimePair(MethodId caller, MethodId callee) implements Comparable<RuntimePair> {
    public RuntimePair {
        if (caller == null || callee == null) {
            throw new NullPointerException("runtime pair methods must not be null");
        }
    }

    @Override
    public int compareTo(RuntimePair other) {
        int callerOrder = caller.value().compareTo(other.caller.value());
        return callerOrder != 0 ? callerOrder : callee.value().compareTo(other.callee.value());
    }
}
