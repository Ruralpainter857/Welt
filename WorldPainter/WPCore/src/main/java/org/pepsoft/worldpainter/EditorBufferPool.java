package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared scratch storage for synchronous editor transactions. Each worker retains at most one idle direct buffer,
 * independent of the number of tools used. An active lease owns its buffer exclusively, including during callbacks.
 * Nested transactions get separate storage; returning them retains only the largest buffer.
 *
 * <p>Buffers and their views must not escape a lease or be used by asynchronous native code. Closing a lease only
 * makes storage reusable; it does not explicitly free native memory. Superseded buffers are collected by the JVM.
 */
final class EditorBufferPool {
    static final int MAX_BYTES = 16 * 1024 * 1024;

    private static final ThreadLocal<State> STATES = ThreadLocal.withInitial(State::new);

    private EditorBufferPool() { }

    static Lease acquire(int bytes) {
        if (bytes < 1 || bytes > MAX_BYTES) {
            throw new IllegalArgumentException("Editor scratch size outside supported bounds");
        }
        State state = STATES.get();
        ByteBuffer buffer = state.idle;
        state.idle = null;
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes);
        }
        buffer.clear().limit(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return new Lease(state, buffer);
    }

    private static final class State {
        ByteBuffer idle;
    }

    static final class Lease implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final State state;
        private ByteBuffer buffer;

        private Lease(State state, ByteBuffer buffer) {
            this.state = state;
            this.buffer = buffer;
        }

        ByteBuffer buffer() {
            checkOwner();
            if (buffer == null) throw new IllegalStateException("Editor scratch lease is closed");
            return buffer;
        }

        @Override public void close() {
            checkOwner();
            if (buffer != null) {
                if (state.idle == null || state.idle.capacity() < buffer.capacity()) state.idle = buffer;
                buffer = null;
            }
        }

        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Editor scratch belongs to another thread");
        }
    }
}
