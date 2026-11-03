package org.pepsoft.worldpainter.importing;

import javax.swing.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Support for the tests of the import dialogs with background loading: running steps on the event dispatch thread,
 * waiting until every load has delivered or discarded its result (a condition, not a delay), holding the loading
 * thread with a latch, and reading private state.
 */
public final class DialogTestSupport {
    private DialogTestSupport() {
        // Prevent instantiation
    }

    /** Run on the event dispatch thread and return the result, rethrowing failures. */
    public static <T> T edt(Callable<T> callable) throws Exception {
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = callable.call();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        } else if (failure[0] instanceof Exception) {
            throw (Exception) failure[0];
        }
        @SuppressWarnings("unchecked") final T typedResult = (T) result[0];
        return typedResult;
    }

    /** Run on the event dispatch thread, rethrowing failures. */
    public static void edtRun(ThrowingRunnable runnable) throws Exception {
        edt(() -> {
            runnable.run();
            return null;
        });
    }

    /** Set the file name field of a dialog, on the event dispatch thread, without waiting for the load. */
    public static void setFile(JDialog dialog, File file) throws Exception {
        edtRun(() -> ((JTextField) field(dialog, "fieldFilename")).setText(file.getAbsolutePath()));
    }

    /**
     * Wait until every image load task has returned and its result has been delivered or discarded on the event
     * dispatch thread, including requests started by that, across all dialogs.
     */
    public static void awaitQuiet() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            if (BackgroundImageLoader.ACTIVE_TASKS.get() == 0) {
                // Every result is posted: deliver it; that may start a waiting request
                SwingUtilities.invokeAndWait(() -> {});
                if (BackgroundImageLoader.ACTIVE_TASKS.get() == 0) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Image loads did not finish");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** Whether the loader of a dialog has a request in progress whose result would be delivered. */
    public static boolean isLoading(JDialog dialog) throws Exception {
        return edt(() -> ((BackgroundImageLoader<?>) field(dialog, "loader")).isLoading());
    }

    /** The counters of the loader of a dialog: delivered, discarded and cancelled results, skipped requests. */
    public static int[] loaderCounters(JDialog dialog) throws Exception {
        return edt(() -> {
            final BackgroundImageLoader<?> loader = (BackgroundImageLoader<?>) field(dialog, "loader");
            return new int[] {loader.deliveredResults, loader.discardedResults, loader.cancelledTasks, loader.skippedRequests};
        });
    }

    /** Whether nothing is waiting, running or undelivered in any loader. */
    public static boolean allIdle() throws Exception {
        return edt(() -> (BackgroundImageLoader.waitingLoaders() == 0) && (BackgroundImageLoader.runningTasks() == 0)
                && (BackgroundImageLoader.ACTIVE_TASKS.get() == 0));
    }

    /**
     * Hold the loading thread before the first {@code count} tasks start, until {@link Hold#release()}; later tasks run
     * freely. Counts the tasks that start. A request replaced or cancelled meanwhile is cancelled before it starts.
     */
    public static Hold hold(int count) {
        return hold(count, false);
    }

    /**
     * Hold the loading thread after the first {@code count} tasks returned their result, before it is delivered, until
     * {@link Hold#release()}: a request replaced or cancelled meanwhile returns a late result, which must be discarded.
     */
    public static Hold holdResults(int count) {
        return hold(count, true);
    }

    /**
     * Hold the loading thread inside the first task, when it first asks whether it was cancelled (for a bounded
     * capture: after reading the image header, before decoding), until {@link Hold#release()}: cancelling meanwhile
     * cancels the task while it runs.
     */
    public static Hold holdInsideTask() {
        final Hold hold = new Hold(1);
        final AtomicInteger polls = new AtomicInteger();
        BackgroundImageLoader.pollHook = cancellation -> {
            if (polls.incrementAndGet() == 1) {
                hold.held.countDown();
                await(hold.release);
            }
        };
        return hold;
    }

    private static Hold hold(int count, boolean afterTask) {
        final Hold hold = new Hold(count);
        BackgroundImageLoader.beforeTaskHook = cancellation -> {
            final int number = hold.started.incrementAndGet();
            if ((! afterTask) && (number <= count)) {
                hold.held.countDown();
                await(hold.release);
            }
        };
        if (afterTask) {
            final AtomicInteger returned = new AtomicInteger();
            BackgroundImageLoader.afterTaskHook = cancellation -> {
                if (returned.incrementAndGet() <= count) {
                    hold.held.countDown();
                    await(hold.release);
                }
            };
        }
        return hold;
    }

    public static final class Hold implements AutoCloseable {
        Hold(int count) {
            held = new CountDownLatch(count);
        }

        /** Wait until the held tasks have reached the hold. */
        public void awaitHeld() {
            await(held);
        }

        public void release() {
            release.countDown();
        }

        /** The number of tasks that started, held or not. */
        public int started() {
            return started.get();
        }

        @Override
        public void close() {
            release.countDown();
            BackgroundImageLoader.beforeTaskHook = null;
            BackgroundImageLoader.afterTaskHook = null;
            BackgroundImageLoader.pollHook = null;
        }

        final CountDownLatch held, release = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
    }

    public static Object field(Object target, String name) {
        try {
            final Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static Object call(Object target, String name) {
        try {
            final Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The number of bounded capture cache files in the temporary directory. */
    public static int cacheFiles() {
        final String[] names = new File(System.getProperty("java.io.tmpdir")).list();
        int count = 0;
        if (names != null) {
            for (String name: names) {
                if (name.startsWith("welt-heightmap-")) {
                    count++;
                }
            }
        }
        return count;
    }

    static void await(CountDownLatch latch) {
        try {
            if (! latch.await(120, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
