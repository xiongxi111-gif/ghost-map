package dev.ghostmap.probe;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Strictly bounded concurrent store for distinct caller-to-callee pairs. */
final class BoundedEdgeStore {
    private final int edgeLimit;
    private final ConcurrentHashMap<EdgeKey, Counter> edges = new ConcurrentHashMap<>();
    private final AtomicInteger reservations = new AtomicInteger();
    private final AtomicLong droppedEvents = new AtomicLong();

    BoundedEdgeStore(int edgeLimit) {
        if (edgeLimit < 1) {
            throw new IllegalArgumentException("edgeLimit must be positive");
        }
        this.edgeLimit = edgeLimit;
    }

    void record(MethodIdentity caller, MethodIdentity callee, RuntimeDispatchKind dispatch) {
        EdgeKey key = new EdgeKey(caller, callee);
        Counter existing = edges.get(key);
        if (existing != null) {
            existing.increment(dispatch);
            return;
        }
        if (!reserve()) {
            existing = edges.get(key);
            if (existing != null) {
                existing.increment(dispatch);
            } else {
                droppedEvents.incrementAndGet();
            }
            return;
        }
        Counter candidate = new Counter(dispatch);
        Counter raced = edges.putIfAbsent(key, candidate);
        if (raced != null) {
            reservations.decrementAndGet();
            raced.increment(dispatch);
        }
    }

    int size() {
        return edges.size();
    }

    int edgeLimit() {
        return edgeLimit;
    }

    long droppedEvents() {
        return droppedEvents.get();
    }

    List<RecordedEdge> snapshot() {
        return edges.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .sorted(Comparator.comparing(RecordedEdge::key))
                .toList();
    }

    private boolean reserve() {
        while (true) {
            int current = reservations.get();
            if (current >= edgeLimit) {
                return false;
            }
            if (reservations.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    record RecordedEdge(EdgeKey key, long hits, RuntimeDispatchKind dispatch) {
        RecordedEdge {
            if (key == null || dispatch == null) {
                throw new NullPointerException("recorded edge fields must not be null");
            }
            if (hits < 1) {
                throw new IllegalArgumentException("hits must be positive");
            }
        }
    }

    private static final class Counter {
        private final LongAdder hits = new LongAdder();
        private RuntimeDispatchKind dispatch;
        private boolean dispatchConflict;

        Counter(RuntimeDispatchKind dispatch) {
            this.dispatch = dispatch;
            hits.increment();
        }

        void increment(RuntimeDispatchKind observed) {
            hits.increment();
            mergeDispatch(observed);
        }

        synchronized void mergeDispatch(RuntimeDispatchKind observed) {
            if (dispatchConflict || observed == RuntimeDispatchKind.UNKNOWN || observed == dispatch) {
                return;
            }
            if (dispatch == RuntimeDispatchKind.UNKNOWN) {
                dispatch = observed;
            } else {
                dispatch = RuntimeDispatchKind.UNKNOWN;
                dispatchConflict = true;
            }
        }

        synchronized RecordedEdge snapshot(EdgeKey key) {
            return new RecordedEdge(key, hits.sum(), dispatch);
        }
    }
}
