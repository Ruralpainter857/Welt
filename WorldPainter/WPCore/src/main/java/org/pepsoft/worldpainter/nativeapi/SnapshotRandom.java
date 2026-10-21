package org.pepsoft.worldpainter.nativeapi;

import java.util.Random;

/** Java Random with an explicit 48-bit state for exclusive native transactions. */
public final class SnapshotRandom extends Random {
    private static final long serialVersionUID = 1L;
    private static final long MULTIPLIER = 0x5DEECE66DL, ADDEND = 0xBL, MASK = (1L << 48) - 1;
    private long state;

    public SnapshotRandom() { super(); }
    public SnapshotRandom(long seed) { super(seed); }

    @Override public synchronized void setSeed(long seed) {
        super.setSeed(seed);
        state = (seed ^ MULTIPLIER) & MASK;
    }
    @Override protected synchronized int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }
    /** The caller holds this monitor across snapshot, JNI and successful commit. */
    public long snapshotState() {
        requireExclusive(); return state;
    }
    /** Native code returns its exact final LCG state; failed calls never commit it. */
    public void restoreState(long nativeState) {
        requireExclusive();
        if ((nativeState & ~MASK) != 0) throw new IllegalArgumentException("Invalid 48-bit random state");
        state = nativeState;
    }
    private void requireExclusive() {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("Random transaction monitor required");
    }
}
