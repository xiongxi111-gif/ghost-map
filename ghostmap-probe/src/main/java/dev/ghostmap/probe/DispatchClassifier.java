package dev.ghostmap.probe;

import java.util.Set;

/** Conservative dispatch classification from an instrumented event's live JVM frames. */
final class DispatchClassifier {
    private static final StackWalker CALLER_FALLBACK = StackWalker.getInstance(Set.of(
            StackWalker.Option.RETAIN_CLASS_REFERENCE,
            StackWalker.Option.SHOW_REFLECT_FRAMES,
            StackWalker.Option.SHOW_HIDDEN_FRAMES));

    private DispatchClassifier() {
    }

    static RuntimeDispatchKind classify(MethodIdentity callee) {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        if (matches(frames, DispatchClassifier::isReflectionFrame)) {
            return RuntimeDispatchKind.REFLECTION;
        }
        if (matches(frames, DispatchClassifier::isProxyFrame)) {
            return RuntimeDispatchKind.PROXY;
        }
        if (callee.name().startsWith("lambda$") || matches(frames, DispatchClassifier::isLambdaFrame)) {
            return RuntimeDispatchKind.LAMBDA;
        }
        if (isGetBean(callee)) {
            return RuntimeDispatchKind.DYNAMIC;
        }
        if (isRegistry(callee)) {
            return RuntimeDispatchKind.REGISTRY;
        }
        return RuntimeDispatchKind.UNKNOWN;
    }

    /** Used only when an instrumented entry has no caller in the thread-local shadow stack. */
    static MethodIdentity fallbackCaller(MethodIdentity callee, ProbeOptions options) {
        return CALLER_FALLBACK.walk(stream -> stream
                .limit(24)
                .filter(frame -> options.includesType(frame.getClassName()))
                .map(frame -> new MethodIdentity(frame.getClassName(), frame.getMethodName(), frame.getDescriptor()))
                .filter(method -> !method.equals(callee))
                .findFirst()
                .orElse(null));
    }

    private static boolean matches(StackTraceElement[] frames,
                                   java.util.function.Predicate<StackTraceElement> predicate) {
        int limit = Math.min(frames.length, 32);
        for (int index = 0; index < limit; index++) {
            if (predicate.test(frames[index])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReflectionFrame(StackTraceElement frame) {
        String owner = frame.getClassName();
        return (owner.equals("java.lang.reflect.Method") && frame.getMethodName().equals("invoke"))
                || owner.startsWith("jdk.internal.reflect.")
                || owner.startsWith("sun.reflect.");
    }

    private static boolean isProxyFrame(StackTraceElement frame) {
        String owner = frame.getClassName();
        return owner.startsWith("jdk.proxy")
                || owner.startsWith("com.sun.proxy.$Proxy")
                || owner.contains(".$Proxy");
    }

    private static boolean isLambdaFrame(StackTraceElement frame) {
        return frame.getMethodName().startsWith("lambda$") || frame.getClassName().contains("$$Lambda$");
    }

    private static boolean isGetBean(MethodIdentity callee) {
        if (!callee.name().equals("getBean")) {
            return false;
        }
        String owner = callee.owner();
        return owner.endsWith("BeanFactory") || owner.endsWith("ApplicationContext")
                || owner.endsWith("BeanRegistry");
    }

    private static boolean isRegistry(MethodIdentity callee) {
        String owner = callee.owner().toLowerCase(java.util.Locale.ROOT);
        String name = callee.name().toLowerCase(java.util.Locale.ROOT);
        return owner.contains("registry")
                && (name.equals("get") || name.equals("lookup") || name.equals("resolve")
                || name.equals("lookuphandler"));
    }
}
