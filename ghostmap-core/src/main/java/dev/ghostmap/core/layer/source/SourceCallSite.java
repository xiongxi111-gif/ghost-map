package dev.ghostmap.core.layer.source;

import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.SourceLocation;

/**
 * Source-only call-site identity. It remains provisional until L2 supplies a bytecode offset.
 * The format is {@code p:s:<enclosing-symbol>@<line>:<column>}.
 */
public record SourceCallSite(String value, SymbolId enclosingMethod, SourceLocation location, int column) {
    public SourceCallSite(SymbolId enclosingMethod, SourceLocation location, int column) {
        this(format(enclosingMethod, location, column), enclosingMethod, location, column);
    }

    public SourceCallSite {
        if (value == null || enclosingMethod == null || location == null) {
            throw new NullPointerException("source call site fields must not be null");
        }
        if (enclosingMethod.kind() != dev.ghostmap.model.NodeKind.METHOD) {
            throw new IllegalArgumentException("call site must be enclosed by a method");
        }
        if (column < 1 || !value.equals(format(enclosingMethod, location, column))) {
            throw new IllegalArgumentException("invalid source call site identity");
        }
    }

    private static String format(SymbolId method, SourceLocation location, int column) {
        if (method == null || location == null) {
            throw new NullPointerException("method and location");
        }
        return "p:s:" + method.value() + '@' + location.line() + ':' + column;
    }
}
