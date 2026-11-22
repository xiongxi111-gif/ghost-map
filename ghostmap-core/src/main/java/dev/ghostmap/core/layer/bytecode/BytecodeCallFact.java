package dev.ghostmap.core.layer.bytecode;

import dev.ghostmap.core.layer.EdgeFact;
import dev.ghostmap.core.layer.SymbolId;
import dev.ghostmap.model.CanonicalId.SiteId;
import dev.ghostmap.model.DispatchKind;
import dev.ghostmap.model.EdgeKind;
import dev.ghostmap.model.Evidence;

import java.util.Optional;
import java.util.OptionalInt;

/** Exact JVM invocation evidence. For invokedynamic, owner names the bootstrap method owner. */
public record BytecodeCallFact(SymbolId from, Optional<SymbolId> target, SiteId site,
                               JvmInvocationKind invocationKind, String owner, String methodName,
                               String descriptor, Evidence.Bytecode evidence, OptionalInt sourceLine,
                               Optional<String> sourceFile, BytecodeDynamicKind dynamicKind,
                               DispatchKind dispatch, Optional<String> bootstrapMethod,
                               Optional<String> constantStringArgument) implements EdgeFact {
    public BytecodeCallFact(SymbolId from, Optional<SymbolId> target, SiteId site,
                            JvmInvocationKind invocationKind, String owner, String methodName,
                            String descriptor, Evidence.Bytecode evidence, OptionalInt sourceLine,
                            Optional<String> sourceFile, BytecodeDynamicKind dynamicKind,
                            DispatchKind dispatch, Optional<String> bootstrapMethod) {
        this(from, target, site, invocationKind, owner, methodName, descriptor, evidence, sourceLine,
                sourceFile, dynamicKind, dispatch, bootstrapMethod, Optional.empty());
    }

    public BytecodeCallFact {
        if (from == null || target == null || site == null || invocationKind == null || owner == null
                || methodName == null || descriptor == null || evidence == null || sourceLine == null
                || sourceFile == null || dynamicKind == null || dispatch == null || bootstrapMethod == null
                || constantStringArgument == null) {
            throw new NullPointerException("bytecode call fields must not be null");
        }
        if (!from.value().equals(site.method().value())) {
            throw new IllegalArgumentException("call site must belong to the caller method");
        }
        if (evidence.offset() != site.bytecodeOffset() || !evidence.owner().equals(owner)
                || !evidence.name().equals(methodName) || !evidence.descriptor().equals(descriptor)) {
            throw new IllegalArgumentException("bytecode evidence must describe the invocation");
        }
        if (owner.isBlank() || methodName.isBlank() || descriptor.isBlank()) {
            throw new IllegalArgumentException("invocation owner, name, and descriptor must not be blank");
        }
        if (sourceLine.isPresent() && sourceLine.orElseThrow() < 1) {
            throw new IllegalArgumentException("source line must be positive");
        }
        sourceFile.ifPresent(file -> {
            if (file.isBlank()) {
                throw new IllegalArgumentException("source file must not be blank");
            }
        });
    }

    @Override
    public EdgeKind kind() {
        return EdgeKind.CALLS;
    }
}
