package dev.ghostmap.model;

import dev.ghostmap.model.CanonicalId.SiteId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Deterministic edge-id construction defined by the snapshot schema. */
public final class EdgeIds {
    private EdgeIds() {
    }

    public static EdgeId canonical(EdgeKind kind, CanonicalId from, Optional<CanonicalId> target,
                                   Optional<SiteId> site) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (from == null) {
            throw new NullPointerException("from");
        }
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (site == null) {
            throw new NullPointerException("site");
        }
        String input = kind + "|" + from.value() + "|" + target.map(CanonicalId::value).orElse("")
                + "|" + site.map(CanonicalId::value).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return new EdgeId("sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }
}
