package dev.ghostmap.probe;

import net.bytebuddy.asm.Advice;

/** Inlined entry/exit advice installed in included target methods. */
final class ProbeAdvice {
    private ProbeAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean enter(@Advice.Origin("#t") String owner,
                         @Advice.Origin("#m") String name,
                         @Advice.Origin("#d") String descriptor) {
        return ProbeRuntime.enter(owner, name, descriptor);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Advice.Enter boolean entered) {
        ProbeRuntime.exit(entered);
    }
}
