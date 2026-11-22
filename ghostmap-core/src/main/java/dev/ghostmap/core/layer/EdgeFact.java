package dev.ghostmap.core.layer;

import dev.ghostmap.model.EdgeKind;

/** Unjoined relationship evidence emitted by an analysis layer. */
public interface EdgeFact {
    EdgeKind kind();

    SymbolId from();
}
