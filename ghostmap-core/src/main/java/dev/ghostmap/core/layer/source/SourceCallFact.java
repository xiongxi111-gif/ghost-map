package dev.ghostmap.core.layer.source;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.TypeId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;

import java.util.List;
import java.util.Optional;

/** A source-level method invocation candidate with receiver and literal evidence. */
public record SourceCallFact(SymbolId from, SourceCallSite site, Evidence.Ast evidence,
                             String textualReceiver, String methodName, int arity,
                             Optional<TypeId> receiverType, DynamicCallKind dynamicKind,
                             DispatchKind dispatch, List<String> stringLiterals) implements EdgeFact {
    public SourceCallFact {
        if (from == null || site == null || evidence == null || textualReceiver == null || methodName == null
                || receiverType == null || dynamicKind == null || dispatch == null || stringLiterals == null) {
            throw new NullPointerException("source call fields must not be null");
        }
        if (!from.equals(site.enclosingMethod())) {
            throw new IllegalArgumentException("call source must match enclosing method");
        }
        if (textualReceiver.isBlank() || methodName.isBlank()) {
            throw new IllegalArgumentException("receiver and method name must not be blank");
        }
        if (arity < 0) {
            throw new IllegalArgumentException("arity must not be negative");
        }
        stringLiterals = List.copyOf(stringLiterals);
        if (dynamicKind == DynamicCallKind.NONE && !stringLiterals.isEmpty()) {
            throw new IllegalArgumentException("literal evidence is reserved for known dynamic APIs");
        }
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.CALLS;
    }
}
