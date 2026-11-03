package org.pepsoft.worldpainter.importing;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * The request and ownership rules of {@link BackgroundImageLoader}, with tasks held by latches: no sleeps and no
 * timing thresholds (the timeouts only stop a broken test).
 */
public class BackgroundImageLoaderTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @After public void reset() throws Exception {
        BackgroundImageLoader.beforeTaskHook = null;
        System.clearProperty("welt.import.asyncImages");
        DialogTestSupport.awaitQuiet();
        assertTrue("no load waiting, running or undelivered", DialogTestSupport.allIdle());
    }

    @Test public void theLastRequestWinsAndALateResultIsDiscarded() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch releaseA = new CountDownLatch(1), aStarted = new CountDownLatch(1), cDelivered = new CountDownLatch(1);
        final List<String> started = Collections.synchronizedList(new ArrayList<>());
        onEdt(() -> {
            // A ignores its cancellation, like a reader which cannot be interrupted
            loader.load(cancellation -> { started.add("A"); aStarted.countDown(); await(releaseA); return "A"; }, recorder.delivered::add, recorder.failures::add);
            loader.load(cancellation -> { started.add("B"); return "B"; }, recorder.delivered::add, recorder.failures::add);
            loader.load(cancellation -> { started.add("C"); return "C"; }, result -> { recorder.delivered.add(result); cDelivered.countDown(); }, recorder.failures::add);
            assertTrue(loader.isLoading());
        });
        await(aStarted);
        onEdt(() -> assertEquals("C waits for A to return; B was replaced before it started", List.of("A"), started));
        releaseA.countDown();
        await(cDelivered);
        onEdt(() -> {
            assertEquals(List.of("C"), recorder.delivered);
            assertEquals("A discarded exactly once", List.of("A"), recorder.discarded);
            assertEquals(List.of("A", "C"), started);
            assertTrue(recorder.failures.isEmpty());
            assertFalse(loader.isLoading());
            assertEquals(1, loader.deliveredResults);
            assertEquals(1, loader.discardedResults);
        });
    }

    @Test public void rapidReplacementsRunAtMostTheFirstAndTheLastTask() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<Integer> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch releaseFirst = new CountDownLatch(1), lastDelivered = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger(), maxRunning = new AtomicInteger();
        final List<Integer> started = Collections.synchronizedList(new ArrayList<>());
        onEdt(() -> {
            for (int i = 0; i < 50; i++) {
                final int request = i;
                loader.load(cancellation -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        started.add(request);
                        if (request == 0) {
                            await(releaseFirst);
                        }
                        return request;
                    } finally {
                        running.decrementAndGet();
                    }
                }, result -> { recorder.delivered.add(result); if (result == 49) lastDelivered.countDown(); }, recorder.failures::add);
            }
        });
        releaseFirst.countDown();
        await(lastDelivered);
        DialogTestSupport.awaitQuiet();
        onEdt(() -> {
            // The first request was handed to a thread at once; cancelled before its task began, it does not run at all
            assertTrue(started.toString(), started.equals(List.of(0, 49)) || started.equals(List.of(49)));
            assertEquals(List.of(49), recorder.delivered);
            assertEquals(started.contains(0) ? List.of(0) : List.of(), recorder.discarded);
            assertEquals(started.contains(0) ? 0 : 1, loader.skippedRequests);
            assertEquals("one task of a loader at a time", 1, maxRunning.get());
        });
    }

    @Test public void aStaleFailureIsNeitherReportedNorAffectsTheCurrentResult() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch releaseA = new CountDownLatch(1), aStarted = new CountDownLatch(1), bDelivered = new CountDownLatch(1);
        onEdt(() -> {
            loader.load(cancellation -> { aStarted.countDown(); await(releaseA); throw new IllegalStateException("damaged A"); },
                    recorder.delivered::add, recorder.failures::add);
            loader.load(cancellation -> "B", result -> { recorder.delivered.add(result); bDelivered.countDown(); }, recorder.failures::add);
        });
        await(aStarted);
        releaseA.countDown();
        await(bDelivered);
        onEdt(() -> {
            assertEquals(List.of("B"), recorder.delivered);
            assertTrue("no late error", recorder.failures.isEmpty());
            assertTrue(recorder.discarded.isEmpty());
        });
    }

    @Test public void aCurrentFailureIsReported() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch failed = new CountDownLatch(1);
        onEdt(() -> loader.load(cancellation -> { throw new IllegalStateException("damaged"); }, recorder.delivered::add,
                failure -> { recorder.failures.add(failure); failed.countDown(); }));
        await(failed);
        onEdt(() -> {
            assertEquals(1, recorder.failures.size());
            assertEquals("damaged", recorder.failures.get(0).getMessage());
            assertTrue(recorder.delivered.isEmpty());
            assertFalse(loader.isLoading());
        });
    }

    @Test public void closingBeforeTheTaskReturnsDiscardsItsResult() throws Exception {
        final Recorder recorder = new Recorder();
        final CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1), discarded = new CountDownLatch(1);
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(result -> { recorder.discarded.add(result); discarded.countDown(); });
        onEdt(() -> loader.load(cancellation -> { started.countDown(); await(release); return "A"; }, recorder.delivered::add, recorder.failures::add));
        await(started);
        onEdt(() -> {
            loader.close();
            assertFalse(loader.isLoading());
            // Ignored once closed
            loader.load(cancellation -> { throw new AssertionError("ran after close"); }, recorder.delivered::add, recorder.failures::add);
        });
        release.countDown();
        await(discarded);
        onEdt(() -> {
            assertEquals(List.of("A"), recorder.discarded);
            assertTrue(recorder.delivered.isEmpty());
            assertTrue(recorder.failures.isEmpty());
        });
    }

    @Test public void closingAfterDeliveryReleasesNothing() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch delivered = new CountDownLatch(1);
        onEdt(() -> loader.load(cancellation -> "A", result -> { recorder.delivered.add(result); delivered.countDown(); }, recorder.failures::add));
        await(delivered);
        onEdt(() -> {
            loader.close();
            assertEquals(List.of("A"), recorder.delivered);
            assertTrue("the dialog owns a delivered result", recorder.discarded.isEmpty());
        });
    }

    @Test public void aCooperativeTaskStopsWhenCancelled() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch started = new CountDownLatch(1), returned = new CountDownLatch(1), nextDelivered = new CountDownLatch(1);
        onEdt(() -> loader.load(cancellation -> {
            started.countDown();
            try {
                while (true) {
                    cancellation.check();
                    Thread.onSpinWait();
                }
            } finally {
                returned.countDown();
            }
        }, recorder.delivered::add, recorder.failures::add));
        await(started);
        onEdt(loader::cancel);
        await(returned);
        // The next request starts once the cancelled task has been accounted for
        onEdt(() -> loader.load(cancellation -> "next", result -> { recorder.delivered.add(result); nextDelivered.countDown(); }, recorder.failures::add));
        await(nextDelivered);
        onEdt(() -> {
            assertEquals(1, loader.cancelledTasks);
            assertEquals(List.of("next"), recorder.delivered);
            assertTrue(recorder.discarded.isEmpty());
            assertTrue(recorder.failures.isEmpty());
        });
    }

    @Test public void theSharedPoolBoundsTheTasksOfAllLoaders() throws Exception {
        final int loaders = BackgroundImageLoader.MAX_DECODERS + 2;
        final CountDownLatch release = new CountDownLatch(1), poolFull = new CountDownLatch(BackgroundImageLoader.MAX_DECODERS), allDelivered = new CountDownLatch(loaders);
        final AtomicInteger running = new AtomicInteger(), maxRunning = new AtomicInteger();
        final List<BackgroundImageLoader<Integer>> list = new ArrayList<>();
        onEdt(() -> {
            for (int i = 0; i < loaders; i++) {
                final BackgroundImageLoader<Integer> loader = new BackgroundImageLoader<>(result -> fail("discarded"));
                list.add(loader);
                final int request = i;
                loader.load(cancellation -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        poolFull.countDown();
                        await(release);
                        return request;
                    } finally {
                        running.decrementAndGet();
                    }
                }, result -> allDelivered.countDown(), failure -> fail(failure.toString()));
            }
        });
        await(poolFull);
        assertEquals(BackgroundImageLoader.MAX_DECODERS, running.get());
        release.countDown();
        await(allDelivered);
        assertEquals(BackgroundImageLoader.MAX_DECODERS, maxRunning.get());
    }

    @Test public void cancelledAndClosedLoadersLeaveNothingWaitingAndOnlyTheLastRequestStarts() throws Exception {
        final CountDownLatch release = new CountDownLatch(1), poolFull = new CountDownLatch(BackgroundImageLoader.MAX_DECODERS), done = new CountDownLatch(BackgroundImageLoader.MAX_DECODERS + 1);
        final List<String> started = Collections.synchronizedList(new ArrayList<>()), delivered = Collections.synchronizedList(new ArrayList<>());
        final List<BackgroundImageLoader<String>> waiting = new ArrayList<>();
        onEdt(() -> {
            // Both threads held by two dialogs
            for (int i = 0; i < BackgroundImageLoader.MAX_DECODERS; i++) {
                final BackgroundImageLoader<String> holder = new BackgroundImageLoader<>(result -> fail("discarded"));
                final String name = "holder" + i;
                holder.load(cancellation -> { started.add(name); poolFull.countDown(); await(release); return name; },
                        result -> { delivered.add(result); done.countDown(); }, failure -> fail(failure.toString()));
            }
            // Three more dialogs wait: one is cancelled, one closed, one asks twice
            for (int i = 0; i < 3; i++) {
                waiting.add(new BackgroundImageLoader<>(result -> fail("discarded")));
            }
            waiting.get(0).load(cancellation -> { started.add("cancelled"); return "cancelled"; }, delivered::add, failure -> fail(failure.toString()));
            waiting.get(1).load(cancellation -> { started.add("closed"); return "closed"; }, delivered::add, failure -> fail(failure.toString()));
            waiting.get(2).load(cancellation -> { started.add("replaced"); return "replaced"; }, delivered::add, failure -> fail(failure.toString()));
            waiting.get(2).load(cancellation -> { started.add("last"); return "last"; }, result -> { delivered.add(result); done.countDown(); }, failure -> fail(failure.toString()));
            assertEquals(3, BackgroundImageLoader.waitingLoaders());
            waiting.get(0).cancel();
            waiting.get(1).close();
            assertEquals("only the loader with a live request still waits, once", 1, BackgroundImageLoader.waitingLoaders());
            assertFalse(waiting.get(0).isLoading());
            assertTrue(waiting.get(2).isLoading());
        });
        await(poolFull);
        release.countDown();
        await(done);
        DialogTestSupport.awaitQuiet();
        onEdt(() -> {
            assertFalse("never started: " + started, started.contains("cancelled") || started.contains("closed") || started.contains("replaced"));
            assertTrue(started.contains("last"));
            assertTrue(delivered.contains("last"));
            assertEquals("never handed to a thread", 0, waiting.get(0).skippedRequests + waiting.get(1).skippedRequests);
            assertEquals(0, BackgroundImageLoader.waitingLoaders());
            assertEquals(0, BackgroundImageLoader.runningTasks());
        });
    }

    @Test public void aRequestCancelledAfterItWasHandedToAThreadButBeforeItBeganIsNotRun() throws Exception {
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        final CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), nextDelivered = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        BackgroundImageLoader.beforeTaskHook = cancellation -> {
            if (held.getCount() > 0) {
                held.countDown();
                await(release);
            }
        };
        onEdt(() -> loader.load(cancellation -> { runs.incrementAndGet(); return "A"; }, recorder.delivered::add, recorder.failures::add));
        await(held);
        onEdt(loader::cancel);
        release.countDown();
        DialogTestSupport.awaitQuiet();
        onEdt(() -> {
            assertEquals("the task of the cancelled request never ran", 0, runs.get());
            assertEquals(1, loader.skippedRequests);
            assertEquals(0, loader.cancelledTasks);
            assertTrue(recorder.delivered.isEmpty());
            assertTrue(recorder.discarded.isEmpty());
            assertTrue("no error for a request that was cancelled", recorder.failures.isEmpty());
            loader.load(cancellation -> "B", result -> { recorder.delivered.add(result); nextDelivered.countDown(); }, recorder.failures::add);
        });
        await(nextDelivered);
        onEdt(() -> assertEquals(List.of("B"), recorder.delivered));
    }

    @Test public void requestsBeyondTheWaitingBoundAreRejectedExplicitly() throws Exception {
        final CountDownLatch release = new CountDownLatch(1), poolFull = new CountDownLatch(BackgroundImageLoader.MAX_DECODERS),
                allDelivered = new CountDownLatch(BackgroundImageLoader.MAX_DECODERS + BackgroundImageLoader.MAX_WAITING);
        final List<Throwable> rejections = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger runs = new AtomicInteger();
        final BackgroundImageLoader<String> extra = new BackgroundImageLoader<>(result -> fail("discarded"));
        onEdt(() -> {
            for (int i = 0; i < BackgroundImageLoader.MAX_DECODERS + BackgroundImageLoader.MAX_WAITING; i++) {
                final boolean holding = i < BackgroundImageLoader.MAX_DECODERS;
                new BackgroundImageLoader<String>(result -> fail("discarded")).load(cancellation -> {
                    runs.incrementAndGet();
                    if (holding) {
                        poolFull.countDown();
                        await(release);
                    }
                    return "ok";
                }, result -> allDelivered.countDown(), failure -> fail(failure.toString()));
            }
            assertEquals(BackgroundImageLoader.MAX_WAITING, BackgroundImageLoader.waitingLoaders());
            extra.load(cancellation -> { runs.incrementAndGet(); return "extra"; }, result -> fail("delivered"), rejections::add);
            assertEquals("rejected at once", 1, rejections.size());
            assertTrue(rejections.get(0) instanceof RejectedExecutionException);
            assertEquals(1, extra.rejectedRequests);
            assertFalse(extra.isLoading());
            assertEquals(BackgroundImageLoader.MAX_WAITING, BackgroundImageLoader.waitingLoaders());
        });
        await(poolFull);
        release.countDown();
        await(allDelivered);
        DialogTestSupport.awaitQuiet();
        assertEquals("the rejected task never ran", BackgroundImageLoader.MAX_DECODERS + BackgroundImageLoader.MAX_WAITING, runs.get());
    }

    @Test public void withBackgroundLoadingDisabledRequestsRunImmediately() throws Exception {
        System.setProperty("welt.import.asyncImages", "false");
        final Recorder recorder = new Recorder();
        final BackgroundImageLoader<String> loader = new BackgroundImageLoader<>(recorder.discarded::add);
        onEdt(() -> {
            assertFalse(BackgroundImageLoader.isEnabled());
            loader.load(cancellation -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                return "A";
            }, recorder.delivered::add, recorder.failures::add);
            assertEquals("delivered before load() returns", List.of("A"), recorder.delivered);
            assertFalse(loader.isLoading());
        });
    }

    @Test public void readingIsIdenticalToImageIoAndCancellable() throws Exception {
        final BufferedImage source = new BufferedImage(300, 200, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 300; x++) {
                source.getRaster().setSample(x, y, 0, (x * 977 + y * 131) & 0xffff);
            }
        }
        for (String format: new String[] {"PNG", "TIFF"}) {
            final File file = files.newFile("image." + format.toLowerCase());
            assertTrue(ImageIO.write(source, format, file));
            final BufferedImage expected = ImageIO.read(file);
            final BufferedImage actual = BackgroundImageLoader.read(file, new BackgroundImageLoader.Cancellation());
            assertEquals(format, expected.getType(), actual.getType());
            assertEquals(format, expected.getColorModel(), actual.getColorModel());
            assertArrayEquals(format, ((DataBufferUShort) expected.getRaster().getDataBuffer()).getData(), ((DataBufferUShort) actual.getRaster().getDataBuffer()).getData());
            final BackgroundImageLoader.Cancellation cancelled = new BackgroundImageLoader.Cancellation();
            cancelled.cancel();
            try {
                BackgroundImageLoader.read(file, cancelled);
                fail("not cancelled");
            } catch (CancellationException e) {
                // Expected
            }
        }
        final File text = files.newFile("not-an-image.txt");
        java.nio.file.Files.writeString(text.toPath(), "not an image");
        assertNull(ImageIO.read(text));
        assertNull(BackgroundImageLoader.read(text, new BackgroundImageLoader.Cancellation()));
    }

    private static final class Recorder {
        final List<Object> delivered = Collections.synchronizedList(new ArrayList<>()), discarded = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    }

    static void await(CountDownLatch latch) {
        try {
            if (! latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /** Run on the event dispatch thread, rethrowing assertion failures. */
    static void onEdt(Runnable runnable) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        } else if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }
}
