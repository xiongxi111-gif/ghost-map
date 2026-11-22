package dev.ghostmap.probe;

import java.util.concurrent.atomic.AtomicReference;

/** Narrow static bridge used by Byte Buddy advice in target classes. */
public final class ProbeRuntime {
    private static final AtomicReference<ProbeSession> SESSION = new AtomicReference<>();

    private ProbeRuntime() {
    }

    static void install(ProbeSession session) {
        if (!SESSION.compareAndSet(null, session)) {
            throw new IllegalStateException("probe runtime is already installed");
        }
    }

    public static boolean enter(String owner, String name, String descriptor) {
        ProbeSession session = SESSION.get();
        if (session == null) {
            return false;
        }
        try {
            return session.enter(new MethodIdentity(owner, name, descriptor));
        } catch (Throwable failure) {
            session.disable("advice entry failed", failure);
            return false;
        }
    }

    public static void exit(boolean entered) {
        if (!entered) {
            return;
        }
        ProbeSession session = SESSION.get();
        if (session != null) {
            try {
                session.exit();
            } catch (Throwable failure) {
                session.disable("advice exit failed", failure);
            }
        }
    }

    static void disable(String message, Throwable failure) {
        ProbeSession session = SESSION.get();
        if (session != null) {
            session.disable(message, failure);
        }
    }
}
