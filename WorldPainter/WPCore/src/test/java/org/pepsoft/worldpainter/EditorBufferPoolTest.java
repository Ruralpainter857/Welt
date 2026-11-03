package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class EditorBufferPoolTest {
    @Test public void sequentialToolsReuseStorageAndResetTheFrame() throws Exception {
        onWorker(() -> {
            ByteBuffer original;
            try (var first = EditorBufferPool.acquire(1024)) {
                original = first.buffer();
                assertTrue(original.isDirect());
                original.order(ByteOrder.BIG_ENDIAN).position(500);
            }
            try (var next = EditorBufferPool.acquire(300)) {
                assertSame(original, next.buffer());
                assertEquals(0, next.buffer().position());
                assertEquals(300, next.buffer().limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, next.buffer().order());
            }
        });
    }

    @Test public void nestedCallsNeverOverwriteActiveStorageAndRetainOnlyTheLargest() throws Exception {
        onWorker(() -> {
            ByteBuffer larger;
            try (var outer = EditorBufferPool.acquire(1024)) {
                outer.buffer().putLong(0, 123456789);
                try (var inner = EditorBufferPool.acquire(2048)) {
                    larger = inner.buffer();
                    assertNotSame(outer.buffer(), larger);
                    larger.putLong(0, -1);
                    assertEquals(123456789, outer.buffer().getLong(0));
                }
                assertEquals(123456789, outer.buffer().getLong(0));
            }
            try (var next = EditorBufferPool.acquire(512)) { assertSame(larger, next.buffer()); }
        });
    }

    @Test public void exceptionalExitReturnsStorageAndClosedLeasesCannotReturnItTwice() throws Exception {
        onWorker(() -> {
            var first = EditorBufferPool.acquire(1024);
            ByteBuffer original = first.buffer();
            try (first) { throw new IllegalArgumentException("callback failed"); }
            catch (IllegalArgumentException expected) { assertEquals("callback failed", expected.getMessage()); }
            assertThrows(IllegalStateException.class, first::buffer);
            try (var active = EditorBufferPool.acquire(1024)) {
                assertSame(original, active.buffer());
                first.close();
                try (var nested = EditorBufferPool.acquire(1024)) { assertNotSame(active.buffer(), nested.buffer()); }
            }
        });
    }

    @Test public void workersAreIsolatedAndForeignThreadsCannotReturnAnActiveLease() throws Exception {
        try (var outer = EditorBufferPool.acquire(128)) {
            ByteBuffer buffer = outer.buffer();
            onWorker(() -> {
                assertThrows(IllegalStateException.class, outer::buffer);
                assertThrows(IllegalStateException.class, outer::close);
                try (var own = EditorBufferPool.acquire(128)) { assertNotSame(buffer, own.buffer()); }
            });
            assertSame(buffer, outer.buffer());
        }
    }

    @Test public void unsupportedSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EditorBufferPool.acquire(0));
        assertThrows(IllegalArgumentException.class, () -> EditorBufferPool.acquire(-1));
        assertThrows(IllegalArgumentException.class, () -> EditorBufferPool.acquire(EditorBufferPool.MAX_BYTES + 1));
    }

    private static void onWorker(Runnable work) throws Exception {
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { work.run(); } catch (Throwable t) { failure.set(t); } });
        worker.start();
        worker.join(10000);
        assertFalse("Worker did not terminate", worker.isAlive());
        if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
    }
}
