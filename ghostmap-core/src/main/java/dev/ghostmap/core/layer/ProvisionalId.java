package dev.ghostmap.core.layer;

import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.NodeKind;

/**
 * A source identity whose erased JVM descriptor is not yet known.
 *
 * <p>Methods use {@code p:m:<binary-owner>#<name>/<arity>@<start>-<end>}; fields use
 * {@code p:f:<binary-owner>#<name>@<start>-<end>}. R01 can reconcile the stored owner, name,
 * arity, and source range against bytecode without treating this value as a canonical JVM id.</p>
 */
public record ProvisionalId(NodeKind kind, TypeId owner, String name, int arity,
                            int startLine, int endLine) implements SymbolId {
    public ProvisionalId {
        if (kind != NodeKind.METHOD && kind != NodeKind.FIELD) {
            throw new IllegalArgumentException("only methods and fields can have provisional source ids");
        }
        if (owner == null || name == null) {
            throw new NullPointerException("provisional id fields must not be null");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (kind == NodeKind.METHOD && arity < 0) {
            throw new IllegalArgumentException("method arity must not be negative");
        }
        if (kind == NodeKind.FIELD && arity != -1) {
            throw new IllegalArgumentException("field arity must be -1");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid provisional source range");
        }
    }

    @Override
    public String value() {
        String member = kind == NodeKind.METHOD ? "m:" + owner.binaryName() + '#' + name + '/' + arity
                : "f:" + owner.binaryName() + '#' + name;
        return "p:" + member + '@' + startLine + '-' + endLine;
    }

    @Override
    public boolean provisional() {
        return true;
    }

    @Override
    public String toString() {
        return value();
    }
}
