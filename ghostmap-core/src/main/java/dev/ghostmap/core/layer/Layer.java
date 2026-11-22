package dev.ghostmap.core.layer;

import dev.ghostmap.model.LayerId;

/** A deterministic analysis layer over an explicit input. */
public interface Layer {
    LayerId id();

    LayerFacts analyse(LayerInput input);
}
