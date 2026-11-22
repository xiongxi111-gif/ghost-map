package dev.ghostmap.probe;

/** One distinct runtime caller-to-callee method pair. */
record EdgeKey(MethodIdentity caller, MethodIdentity callee) implements Comparable<EdgeKey> {
    EdgeKey {
        if (caller == null || callee == null) {
            throw new NullPointerException("edge methods must not be null");
        }
    }

    @Override
    public int compareTo(EdgeKey other) {
        int callerOrder = caller.compareTo(other.caller);
        return callerOrder != 0 ? callerOrder : callee.compareTo(other.callee);
    }
}
