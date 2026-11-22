package dev.ghostmap.probe;

/** JVM method identity recorded by the runtime probe. */
record MethodIdentity(String owner, String name, String descriptor) implements Comparable<MethodIdentity> {
    MethodIdentity {
        requireText(owner, "owner");
        requireText(name, "name");
        requireText(descriptor, "descriptor");
    }

    @Override
    public int compareTo(MethodIdentity other) {
        int ownerOrder = owner.compareTo(other.owner);
        if (ownerOrder != 0) {
            return ownerOrder;
        }
        int nameOrder = name.compareTo(other.name);
        return nameOrder != 0 ? nameOrder : descriptor.compareTo(other.descriptor);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
