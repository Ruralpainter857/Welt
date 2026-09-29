package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only memory sampling kept separate from benchmark timing runs. */
final class BenchmarkMemorySupport {
    private static final Method RESIDENT_BYTES_METHOD = findResidentBytesMethod();

    @FunctionalInterface
    interface Operation {
        void run() throws Exception;
    }

    private BenchmarkMemorySupport() {
    }

    static Snapshot measure(Operation operation) throws Exception {
        allocatedBytesForThread(Thread.currentThread().getId());
        collectGarbage();
        final long heapStart = usedHeapBytes();
        final long residentStart = residentBytes();
        final AtomicLong heapPeak = new AtomicLong(heapStart);
        final AtomicLong residentPeak = new AtomicLong(residentStart);
        final AtomicBoolean sampling = new AtomicBoolean(true);
        final CountDownLatch workerFinished = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final AtomicLong allocatedBytes = new AtomicLong(-1L);
        final AtomicReference<Throwable> operationFailure = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                operation.run();
            } catch (Throwable failure) {
                operationFailure.set(failure);
            } finally {
                allocatedBytes.set(allocatedBytesForThread(Thread.currentThread().getId()));
                workerFinished.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "welt-benchmark-worker");
        final Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                updatePeak(heapPeak, usedHeapBytes());
                updatePeak(residentPeak, residentBytes());
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "welt-benchmark-memory-sampler");
        sampler.setDaemon(true);
        final Snapshot snapshot;
        try {
            worker.start();
            sampler.start();
            workerFinished.await();
            sampling.set(false);
            sampler.join();
            final long heapEnd = usedHeapBytes();
            final long residentEnd = residentBytes();
            updatePeak(heapPeak, heapEnd);
            updatePeak(residentPeak, residentEnd);
            collectGarbage();
            snapshot = new Snapshot(heapStart, heapPeak.get(), heapEnd, usedHeapBytes(),
                    residentStart, residentPeak.get(), residentEnd, residentBytes(), allocatedBytes.get());
        } finally {
            sampling.set(false);
            sampler.interrupt();
            releaseWorker.countDown();
            worker.join();
            sampler.join();
        }
        final Throwable failure = operationFailure.get();
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
        return snapshot;
    }

    private static void collectGarbage() throws InterruptedException {
        System.gc();
        Thread.sleep(75L);
        System.gc();
        Thread.sleep(75L);
    }

    private static long usedHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long allocatedBytesForThread(long threadId) {
        final java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean)) {
            return -1L;
        }
        final com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) bean;
        try {
            if (!allocationBean.isThreadAllocatedMemorySupported()) {
                return -1L;
            }
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean.getThreadAllocatedBytes(threadId);
        } catch (RuntimeException | LinkageError unavailable) {
            return -1L;
        }
    }

    private static long residentBytes() {
        if (RESIDENT_BYTES_METHOD == null) {
            return -1L;
        }
        try {
            return ((Number) RESIDENT_BYTES_METHOD.invoke(null)).longValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return -1L;
        }
    }

    private static Method findResidentBytesMethod() {
        try {
            final Class<?> nativeSlices = Class.forName(
                    "org.pepsoft.worldpainter.nativeapi.NativeSlices", false,
                    BenchmarkMemorySupport.class.getClassLoader());
            return nativeSlices.getMethod("currentProcessResidentBytes");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    private static void updatePeak(AtomicLong peak, long value) {
        if (value < 0) {
            return;
        }
        long current;
        do {
            current = peak.get();
            if (current >= value) {
                return;
            }
        } while (!peak.compareAndSet(current, value));
    }

    private static String formatMebibytes(long bytes) {
        return bytes < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0);
    }

    static final class Snapshot {
        private final long heapStart;
        private final long heapPeak;
        private final long heapEnd;
        private final long heapAfterGc;
        private final long residentStart;
        private final long residentPeak;
        private final long residentEnd;
        private final long residentAfterGc;
        private final long allocatedBytes;

        private Snapshot(long heapStart, long heapPeak, long heapEnd, long heapAfterGc,
                         long residentStart, long residentPeak, long residentEnd,
                         long residentAfterGc, long allocatedBytes) {
            this.heapStart = heapStart;
            this.heapPeak = heapPeak;
            this.heapEnd = heapEnd;
            this.heapAfterGc = heapAfterGc;
            this.residentStart = residentStart;
            this.residentPeak = residentPeak;
            this.residentEnd = residentEnd;
            this.residentAfterGc = residentAfterGc;
            this.allocatedBytes = allocatedBytes;
        }

        long allocatedBytes() {
            return allocatedBytes;
        }

        @Override
        public String toString() {
            final String allocated = allocatedBytes < 0
                    ? "n/a" : formatMebibytes(allocatedBytes) + " MiB";
            return "heap start/peak/end/post-GC=" + formatMebibytes(heapStart) + "/"
                    + formatMebibytes(heapPeak) + "/" + formatMebibytes(heapEnd) + "/"
                    + formatMebibytes(heapAfterGc) + " MiB; RSS start/peak/end/post-GC="
                    + formatMebibytes(residentStart) + "/" + formatMebibytes(residentPeak) + "/"
                    + formatMebibytes(residentEnd) + "/" + formatMebibytes(residentAfterGc)
                    + " MiB; Java allocated=" + allocated;
        }
    }
}
