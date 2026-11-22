package dev.ghostmap.core.join;

import dev.ghostmap.model.LayerId;

/** One pre-penalty evidence weight supplied to noisy-OR. */
public record EvidenceContribution(LayerId layer, double weight, String reason) {
    public EvidenceContribution {
        if (layer == null || reason == null) {
            throw new NullPointerException("evidence contribution fields must not be null");
        }
        if (!Double.isFinite(weight) || weight < 0.0 || weight >= 1.0) {
            throw new IllegalArgumentException("evidence weight must be in [0, 1)");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("evidence reason must not be blank");
        }
    }
}
