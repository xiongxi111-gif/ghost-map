package dev.ghostmap.model;

import java.time.Instant;

/** Source and class-file version analysed into a snapshot. */
public record CodeVersion(String repo, String commit, boolean dirty, String classHash, Instant analysedAt) {
    public CodeVersion {
        ModelChecks.nonBlank(repo, "repo");
        ModelChecks.nonBlank(commit, "commit");
        requireSha256(classHash, "classHash");
        if (analysedAt == null) {
            throw new NullPointerException("analysedAt");
        }
    }

    static String requireSha256(String value, String name) {
        ModelChecks.nonBlank(value, name);
        String prefix = "sha256:";
        if (!value.startsWith(prefix) || value.length() != prefix.length() + 64) {
            throw new IllegalArgumentException(name + " must be a sha256 hash");
        }
        for (int index = prefix.length(); index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                throw new IllegalArgumentException(name + " must use lowercase hexadecimal");
            }
        }
        return value;
    }
}
