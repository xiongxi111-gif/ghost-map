package dev.ghostmap.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime state for one attached agent run. */
final class ProbeSession {
    private final ProbeOptions options;
    private final ProbeLog log;
    private final RuntimeDumpWriter writer;
    private final BoundedEdgeStore store;
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ThreadLocal<Deque<MethodIdentity>> shadowStack = ThreadLocal.withInitial(ArrayDeque::new);

    ProbeSession(ProbeOptions options, ProbeLog log, RuntimeDumpWriter writer) {
        if (options == null || log == null || writer == null) {
            throw new NullPointerException("probe session fields must not be null");
        }
        this.options = options;
        this.log = log;
        this.writer = writer;
        this.store = new BoundedEdgeStore(options.edgeLimit());
    }

    void start() {
        Thread shutdown = new Thread(this::shutdown, "ghostmap-probe-shutdown");
        shutdown.setContextClassLoader(null);
        Runtime.getRuntime().addShutdownHook(shutdown);
        options.trigger().ifPresent(this::startTriggerWatcher);
        log.info("STARTED runId=" + options.runId() + " edgeLimit=" + options.edgeLimit());
    }

    boolean enter(MethodIdentity callee) {
        if (!enabled.get()) {
            return false;
        }
        try {
            Deque<MethodIdentity> stack = shadowStack.get();
            MethodIdentity caller = stack.peek();
            if (caller == null) {
                caller = DispatchClassifier.fallbackCaller(callee, options);
            }
            if (caller != null) {
                store.record(caller, callee, DispatchClassifier.classify(callee));
            }
            stack.push(callee);
            return true;
        } catch (Throwable failure) {
            disable("recording failed", failure);
            shadowStack.remove();
            return false;
        }
    }

    void exit() {
        try {
            Deque<MethodIdentity> stack = shadowStack.get();
            if (!stack.isEmpty()) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                shadowStack.remove();
            }
        } catch (Throwable failure) {
            disable("shadow stack failed", failure);
            shadowStack.remove();
        }
    }

    void disable(String message, Throwable failure) {
        if (enabled.compareAndSet(true, false)) {
            log.error("DISABLED " + message, failure);
        }
    }

    private void shutdown() {
        try {
            flush("shutdown");
        } finally {
            enabled.set(false);
            shadowStack.remove();
        }
    }

    private synchronized void flush(String reason) {
        try {
            writer.append(options, startedAt, Instant.now(), reason, store);
            log.info("DUMP reason=" + reason + " edges=" + store.size()
                    + " droppedEvents=" + store.droppedEvents());
        } catch (Throwable failure) {
            disable("runtime dump failed", failure);
        }
    }

    private void startTriggerWatcher(Path trigger) {
        Thread watcher = new Thread(() -> watchTrigger(trigger), "ghostmap-probe-trigger");
        watcher.setDaemon(true);
        watcher.setContextClassLoader(null);
        watcher.start();
    }

    private void watchTrigger(Path trigger) {
        long lastFingerprint = Long.MIN_VALUE;
        while (enabled.get()) {
            try {
                if (Files.isRegularFile(trigger)) {
                    long fingerprint = Files.getLastModifiedTime(trigger).toMillis() ^ Files.size(trigger);
                    if (fingerprint != lastFingerprint) {
                        flush("trigger");
                        if (Files.deleteIfExists(trigger)) {
                            lastFingerprint = Long.MIN_VALUE;
                        } else {
                            lastFingerprint = fingerprint;
                        }
                    }
                }
                Thread.sleep(options.triggerPollMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException failure) {
                disable("trigger watcher failed", failure);
                return;
            }
        }
    }
}
