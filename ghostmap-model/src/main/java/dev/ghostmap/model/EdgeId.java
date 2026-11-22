package dev.ghostmap.model;

/** SHA-256 identity derived from an edge's kind, endpoints, and site. */
public record EdgeId(String value) {
    private static final String PREFIX = "sha256:";

    public EdgeId {
        ModelChecks.nonBlank(value, "edge id");
        if (!value.startsWith(PREFIX) || value.length() != PREFIX.length() + 64) {
            throw new IllegalArgumentException("edge id must be a sha256 hash");
        }
        for (int index = PREFIX.length(); index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                throw new IllegalArgumentException("edge id must use lowercase hexadecimal");
            }
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
