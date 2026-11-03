package org.pepsoft.worldpainter.importing;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Loads the image of an import dialog off the event dispatch thread, one per dialog. The last request wins: a newer
 * request, {@link #cancel()} or {@link #close()} cancels the previous one, whose result is then never delivered but
 * {@linkplain #BackgroundImageLoader(Consumer) discarded}, exactly once.
 *
 * <p>A result belongs to its task until it is delivered on the event dispatch thread, after which it belongs to the
 * dialog. All methods must be called on the event dispatch thread, which never waits for a task, and which also holds
 * all scheduling state.
 *
 * <p>At most one task of a loader runs at a time, and at most one more request of it waits: a newer request replaces
 * the waiting one, which never started. A cancelled task must actually return (its reader disposed and its input
 * closed) before the waiting request starts, so that a dialog never holds two images being decoded, even when a reader
 * does not stop early. Across all loaders at most {@link #MAX_DECODERS} tasks run, on a small shared pool of daemon
 * threads; loaders with a request waiting for one of them queue in order, each once, at most {@link #MAX_WAITING} of
 * them. Cancelling or closing a loader takes it out of that queue: a request which has not started never starts, and
 * a request cancelled after it was handed to a thread but before its task began is not run either. Only the actual
 * end of a task frees its thread.
 *
 * <p>When background loading is disabled ({@code -Dwelt.import.asyncImages=false}) the same requests run immediately
 * on the calling thread, as the dialogs did before.
 */
public final class BackgroundImageLoader<R> {
    /**
     * @param discard Releases a result which is not delivered: a request that was replaced or cancelled, or a dialog
     *                that was closed. Called on the event dispatch thread.
     */
    public BackgroundImageLoader(Consumer<? super R> discard) {
        this.discard = discard;
    }

    /**
     * Request a new load, cancelling the current one. Ignored once the loader is closed.
     *
     * @param task     Loads the image, off the event dispatch thread unless background loading is disabled. Expected
     *                 problems (unsupported or damaged files) should be part of the result.
     * @param onLoaded Takes over the result, on the event dispatch thread, if this is still the last request.
     * @param onFailed Reports an unexpected exception of the task, on the event dispatch thread, if this is still the
     *                 last request; or, at once, a {@link RejectedExecutionException} if too many other dialogs are
     *                 already waiting to load an image.
     */
    public void load(Task<R> task, Consumer<? super R> onLoaded, Consumer<? super Throwable> onFailed) {
        if (closed) {
            return;
        }
        cancel();
        pending = new Request<>(task, onLoaded, onFailed);
        schedule();
    }

    /** Cancel the current request, if any; its result will be discarded. A request which has not started is dropped. */
    public void cancel() {
        pending = null;
        WAITING.remove(this);
        if (running != null) {
            running.cancellation.cancel();
        }
    }

    /** Cancel the current request and deliver nothing anymore. A task still running is discarded when it returns. */
    public void close() {
        cancel();
        closed = true;
    }

    /** Whether a request is in progress whose result would still be delivered. */
    public boolean isLoading() {
        return (pending != null) || ((running != null) && (! running.cancellation.cancelled));
    }

    /** Whether images are loaded in the background ({@code -Dwelt.import.asyncImages}, {@code true} by default). */
    public static boolean isEnabled() {
        return ! "false".equalsIgnoreCase(System.getProperty("welt.import.asyncImages"));
    }

    /**
     * Read an image as {@link ImageIO#read(File)} does, with the same reader and parameters, but aborting the read as
     * soon as {@code cancellation} is cancelled, from the reading thread itself.
     *
     * @return The image, or {@code null} if no reader recognises the file.
     * @throws CancellationException If the read was cancelled.
     */
    public static BufferedImage read(File file, Cancellation cancellation) throws IOException {
        if (! file.canRead()) {
            throw new IIOException("Can't read input file!");
        }
        final ImageInputStream stream = ImageIO.createImageInputStream(file);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        try {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (! readers.hasNext()) {
                return null;
            }
            final ImageReader reader = readers.next();
            try {
                final ImageReadParam param = reader.getDefaultReadParam();
                reader.setInput(stream, true, true);
                reader.addIIOReadProgressListener(new IIOReadProgressListener() {
                    @Override public void imageProgress(ImageReader source, float percentageDone) {
                        if (cancellation.isCancelled()) {
                            source.abort();
                        }
                    }

                    @Override public void sequenceStarted(ImageReader source, int minIndex) {}
                    @Override public void sequenceComplete(ImageReader source) {}
                    @Override public void imageStarted(ImageReader source, int imageIndex) {}
                    @Override public void imageComplete(ImageReader source) {}
                    @Override public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex) {}
                    @Override public void thumbnailProgress(ImageReader source, float percentageDone) {}
                    @Override public void thumbnailComplete(ImageReader source) {}
                    @Override public void readAborted(ImageReader source) {}
                });
                cancellation.check();
                final BufferedImage image = reader.read(0, param);
                // An aborted read returns a partially decoded image
                cancellation.check();
                return image;
            } finally {
                reader.dispose();
            }
        } finally {
            stream.close();
        }
    }

    /** Start the waiting request, if it can start now, or queue this loader for a free thread. */
    private void schedule() {
        if ((running != null) || (pending == null) || closed) {
            // Starts when the running task has returned
            return;
        }
        if (! isEnabled()) {
            runNow(takePending());
        } else if (runningTasks < MAX_DECODERS) {
            start(takePending());
        } else if (! WAITING.contains(this)) {
            if (WAITING.size() >= MAX_WAITING) {
                final Request<R> rejected = takePending();
                rejectedRequests++;
                rejected.onFailed.accept(new RejectedExecutionException("Too many images waiting to be loaded"));
            } else {
                WAITING.addLast(this);
            }
        }
    }

    private Request<R> takePending() {
        final Request<R> request = pending;
        pending = null;
        return request;
    }

    /** Run a request on the calling thread: background loading is disabled. */
    private void runNow(Request<R> request) {
        running = request;
        R result = null;
        Throwable failure = null;
        try {
            result = request.task.load(request.cancellation);
        } catch (Throwable t) {
            failure = t;
        }
        finished(request, result, failure, false, false);
    }

    private void start(Request<R> request) {
        running = request;
        runningTasks++;
        ACTIVE_TASKS.incrementAndGet();
        try {
            EXECUTOR.execute(() -> {
                R result = null;
                Throwable failure = null;
                boolean started = false;
                try {
                    final Consumer<Cancellation> before = beforeTaskHook;
                    if (before != null) {
                        before.accept(request.cancellation);
                    }
                    // Not run at all if cancelled before it began
                    if (! request.cancellation.cancelled) {
                        started = true;
                        result = request.task.load(request.cancellation);
                        final Consumer<Cancellation> after = afterTaskHook;
                        if (after != null) {
                            after.accept(request.cancellation);
                        }
                    }
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    // The task has returned: only now may the next request of this loader, or of another, start
                    final R finalResult = result;
                    final Throwable finalFailure = failure;
                    final boolean skipped = ! started;
                    SwingUtilities.invokeLater(() -> finished(request, finalResult, finalFailure, skipped, true));
                    ACTIVE_TASKS.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            // Cannot happen while at most MAX_DECODERS tasks are submitted, but never leaks a thread slot if it does
            runningTasks--;
            ACTIVE_TASKS.decrementAndGet();
            running = null;
            rejectedRequests++;
            request.onFailed.accept(e);
        }
    }

    private void finished(Request<R> request, R result, Throwable failure, boolean skipped, boolean onThread) {
        running = null;
        if (onThread) {
            runningTasks--;
        }
        final boolean current = (! closed) && (! request.cancellation.cancelled);
        if (current) {
            if (failure == null) {
                deliveredResults++;
                request.onLoaded.accept(result);
            } else {
                request.onFailed.accept(failure);
            }
        } else {
            if (result != null) {
                discardedResults++;
                discard.accept(result);
            }
            if (skipped) {
                skippedRequests++;
            } else if (failure instanceof CancellationException) {
                cancelledTasks++;
            } else if (failure != null) {
                logger.debug("Ignoring failure of cancelled image load", failure);
            }
        }
        // A thread may be free: the loaders waiting longest first, then this one
        while ((runningTasks < MAX_DECODERS) && (! WAITING.isEmpty())) {
            WAITING.pollFirst().schedule();
        }
        schedule();
    }

    /** The number of loaders waiting for a free thread, for tests and diagnostics. Event dispatch thread only. */
    static int waitingLoaders() {
        return WAITING.size();
    }

    /** The number of tasks submitted and not yet finished on the event dispatch thread. Event dispatch thread only. */
    static int runningTasks() {
        return runningTasks;
    }

    /** Loads an image and whatever the dialog needs to show it, off the event dispatch thread. */
    @FunctionalInterface
    public interface Task<R> {
        /**
         * @param cancellation Whether the result is no longer wanted; check it between steps and stop early.
         * @throws CancellationException If the task stopped because it was cancelled.
         */
        R load(Cancellation cancellation) throws Exception;
    }

    /** Whether a request was cancelled: by a newer request, {@link #cancel()} or {@link #close()}. */
    public static final class Cancellation implements BooleanSupplier {
        public boolean isCancelled() {
            final Consumer<Cancellation> hook = pollHook;
            if (hook != null) {
                hook.accept(this);
            }
            return cancelled;
        }

        @Override
        public boolean getAsBoolean() {
            return isCancelled();
        }

        /** @throws CancellationException If cancelled. */
        public void check() {
            if (isCancelled()) {
                throw new CancellationException("Image load cancelled");
            }
        }

        void cancel() {
            cancelled = true;
        }

        private volatile boolean cancelled;
    }

    private static final class Request<R> {
        Request(Task<R> task, Consumer<? super R> onLoaded, Consumer<? super Throwable> onFailed) {
            this.task = task;
            this.onLoaded = onLoaded;
            this.onFailed = onFailed;
        }

        final Task<R> task;
        final Consumer<? super R> onLoaded;
        final Consumer<? super Throwable> onFailed;
        final Cancellation cancellation = new Cancellation();
    }

    private final Consumer<? super R> discard;
    private Request<R> running, pending;
    private boolean closed;
    /** Counters for tests and diagnostics. */
    int deliveredResults, discardedResults, cancelledTasks, skippedRequests, rejectedRequests;

    /** The most tasks running at the same time, across all dialogs. */
    static final int MAX_DECODERS = 2;
    /** The most loaders waiting for a thread at the same time; a request beyond that is rejected. */
    static final int MAX_WAITING = 8;
    /**
     * Tasks submitted whose result has not been handed to the event dispatch thread yet, for tests and diagnostics:
     * when it is zero, flushing the event queue delivers or discards every result.
     */
    static final AtomicInteger ACTIVE_TASKS = new AtomicInteger();
    /** Run on the loading thread before each task and after it returned a result, for tests that need to hold it. */
    static volatile Consumer<Cancellation> beforeTaskHook, afterTaskHook;
    /** Run whenever a task asks whether it was cancelled, for tests that need to hold it inside a task. */
    static volatile Consumer<Cancellation> pollHook;

    /** The loaders waiting for a thread, in order, each once. Event dispatch thread only. */
    private static final Deque<BackgroundImageLoader<?>> WAITING = new ArrayDeque<>();
    /** Tasks handed to the threads and not yet finished on the event dispatch thread. Event dispatch thread only. */
    private static int runningTasks;
    private static final ThreadPoolExecutor EXECUTOR;

    static {
        final AtomicInteger threadNumber = new AtomicInteger();
        // At most MAX_DECODERS tasks are submitted at a time, so the queue only holds a task until a thread which has
        // just posted its previous result takes it, and never rejects one
        EXECUTOR = new ThreadPoolExecutor(MAX_DECODERS, MAX_DECODERS, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_DECODERS), runnable -> {
            final Thread thread = new Thread(runnable, "Image loader " + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BackgroundImageLoader.class);
}
