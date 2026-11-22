package dev.ghostmap.core.layer.bytecode;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.NodeKind;

/** Class-file superclass or interface relationship. */
public record BytecodeHierarchyFact(EdgeKind kind, SymbolId from, SymbolId target, String classFile) implements EdgeFact {
    public BytecodeHierarchyFact {
        if (kind != EdgeKind.EXTENDS && kind != EdgeKind.IMPLEMENTS) {
            throw new IllegalArgumentException("hierarchy kind must be EXTENDS or IMPLEMENTS");
        }
        if (from == null || target == null || classFile == null) {
            throw new NullPointerException("hierarchy fact fields must not be null");
        }
        if (from.kind() != NodeKind.TYPE || target.kind() != NodeKind.TYPE) {
            throw new IllegalArgumentException("hierarchy endpoints must be types");
        }
        if (classFile.isBlank()) {
            throw new IllegalArgumentException("classFile must not be blank");
        }
    }
}
