package com.tricrotism.windchill.agent;

import java.util.concurrent.atomic.LongAdder;

/**
 * Invocation counters for instrumented methods.
 *
 * <p>Injected bytecode calls {@link #hit(int)} at method entry, so this class has to be reachable
 * from every instrumented plugin class. It is loaded by the system class loader (the agent jar is
 * appended there on attach), which every plugin class loader delegates to.
 *
 * <p>Slots are allocated once, before any instrumentation is installed, and never resized. A
 * {@code LongAdder} per slot rather than an {@code AtomicLong} because the hot case is many region
 * threads hitting the same method, which is exactly the contention LongAdder is for.
 */
public final class MethodCounters {

    private static volatile LongAdder[] counters = new LongAdder[0];

    private MethodCounters() {}

    /**
     * Sizes the counter table. Called once per instrumentation session before any transformed class
     * can run, so the array read in {@link #hit(int)} never races a resize.
     */
    public static void allocate(int slots) {
        LongAdder[] fresh = new LongAdder[slots];
        for (int i = 0; i < slots; i++) fresh[i] = new LongAdder();
        counters = fresh;
    }

    /** Entry point for injected bytecode. Must never throw: it runs inside arbitrary plugin code. */
    public static void hit(int slot) {
        LongAdder[] local = counters;
        if (slot >= 0 && slot < local.length) local[slot].increment();
    }

    public static long[] read() {
        LongAdder[] local = counters;
        long[] out = new long[local.length];
        for (int i = 0; i < local.length; i++) out[i] = local[i].sum();
        return out;
    }
}
