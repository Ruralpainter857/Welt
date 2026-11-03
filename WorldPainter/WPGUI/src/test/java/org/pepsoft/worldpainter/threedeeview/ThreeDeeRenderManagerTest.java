package org.pepsoft.worldpainter.threedeeview;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Real queued rendering, including waiting, failures, stopping and restarting a generation. */
public class ThreeDeeRenderManagerTest {
    private final Map<String,String> previous = new HashMap<>();
    private ExecutorService waiter;
    @Before public void prepare() {
        boolean available = !GraphicsEnvironment.isHeadless() && NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireIsometric")) assertTrue(available); else Assume.assumeTrue(available);
        for (String key : new String[]{Native.GEN_KEY,Native.RENDER_KEY,"welt.native.isometric","welt.native.viewport"}) previous.put(key,System.getProperty(key));
        System.setProperty(Native.GEN_KEY,"false"); System.setProperty("welt.native.viewport","false");
        waiter = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task,"3D batch test waiter"); thread.setDaemon(true); return thread; });
    }
    @After public void restore() {
        if (waiter != null) waiter.shutdownNow();
        previous.forEach((key,value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key,value); });
    }
    private static ThreeDeeRenderManager manager(Dimension dimension) {
        ThreeDeeRenderManager manager = new ThreeDeeRenderManager(dimension,ColourScheme.DEFAULT,new CustomBiomeManager(),0);
        manager.setLayerVisibility(Tile3DRenderer.LayerVisibilityMode.SURFACE); manager.setHiddenLayers(Collections.emptySet()); return manager;
    }
    private void await(ThreeDeeRenderManager manager) throws Exception {
        waiter.submit(() -> { manager.renderAllTiles(); return null; }).get(10,TimeUnit.SECONDS);
    }
    @Test public void completeQueuedNativeRedrawsPublishExactPixelsBeforeReturning() throws Exception {
        Dimension dimension = IsometricSceneBenchmark.scene(); ThreeDeeRenderManager manager = manager(dimension);
        try {
            for (int round = 0; round < 2; round++) {
                dimension.getTile(0,0).setHeight(7,9,65 + round * 31);
                IsometricRenderParityTest.select(false);
                var reference = new Tile3DRenderer(dimension,ColourScheme.DEFAULT,new CustomBiomeManager(),0,Tile3DRenderer.LayerVisibilityMode.SURFACE,Collections.emptySet());
                Map<Tile,BufferedImage> expected = new HashMap<>(); for (Tile tile : dimension.getTiles()) expected.put(tile,reference.render(tile));
                IsometricRenderParityTest.select(true); long before = IsometricRenderAccess.completedTiles();
                for (Tile tile : dimension.getTiles()) manager.renderTile(tile);
                await(manager); Set<RenderResult> results = manager.getRenderedTiles();
                assertEquals(expected.size(),results.size()); assertEquals(expected.size(),IsometricRenderAccess.completedTiles()-before);
                for (RenderResult result : results) IsometricRenderParityTest.compare(expected.get(result.getTile()),result.getImage(),"queued round="+round);
                assertTrue(manager.getRenderedTiles().isEmpty());
            }
        } finally { manager.stop(); }
    }
    @Test public void failingTileDoesNotStrandTheQueueOrLoseOtherResults() throws Exception {
        Dimension dimension = IsometricSceneBenchmark.scene(); ThreeDeeRenderManager manager = manager(dimension);
        Tile broken = new Tile(2,2,-64,320) { @Override public Terrain getTerrain(int x,int y) { throw new IllegalStateException("Injected render failure"); } };
        dimension.addTile(broken);
        try {
            IsometricRenderParityTest.select(true); manager.renderTile(broken); manager.renderTile(dimension.getTile(-1,-1)); await(manager);
            Set<RenderResult> results = manager.getRenderedTiles(); assertEquals(2,results.size());
            assertEquals(1,results.stream().filter(result -> result.getImage() == ThreeDeeView.TILE_NOT_RENDERABLE).count());
            manager.renderTile(dimension.getTile(0,0)); await(manager); assertEquals(1,manager.getRenderedTiles().size());
        } finally { manager.stop(); }
    }
    @Test public void stopReleasesWaitersAndOldWorkersCannotPublishIntoTheNewQueue() throws Exception {
        Dimension dimension = IsometricSceneBenchmark.scene(); ThreeDeeRenderManager manager = manager(dimension);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true); AtomicReference<Thread> oldWorker = new AtomicReference<>();
        Tile blocked = new Tile(2,2,-64,320) {
            @Override public Terrain getTerrain(int x,int y) {
                if (first.compareAndSet(true,false)) {
                    oldWorker.set(Thread.currentThread()); entered.countDown();
                    for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { /* Hold the old job until the test releases it. */ } }
                }
                return Terrain.STONE;
            }
        };
        try {
            IsometricRenderParityTest.select(true); manager.renderTile(blocked); assertTrue(entered.await(10,TimeUnit.SECONDS));
            CountDownLatch awaiting = new CountDownLatch(1); AtomicReference<Thread> waitingThread = new AtomicReference<>();
            Future<?> waiting = waiter.submit(() -> { waitingThread.set(Thread.currentThread()); awaiting.countDown(); manager.renderAllTiles(); return null; });
            assertTrue(awaiting.await(10,TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waitingThread.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals("The batch must actually be waiting for the held job",Thread.State.WAITING,waitingThread.get().getState());
            manager.stop(); waiting.get(10,TimeUnit.SECONDS);
            Tile current = dimension.getTile(-1,-1); manager.renderTile(current); await(manager);
            Set<RenderResult> results = manager.getRenderedTiles(); assertEquals(1,results.size()); assertSame(current,results.iterator().next().getTile());
            release.countDown(); oldWorker.get().join(10000); assertFalse("Stopped worker must exit",oldWorker.get().isAlive());
            assertTrue("No stale result from the stopped generation",manager.getRenderedTiles().isEmpty());
        } finally { release.countDown(); manager.stop(); }
    }
    @Test public void replacingATileDuringRenderingDiscardsTheOldImage() throws Exception {
        Dimension dimension = IsometricSceneBenchmark.scene(); ThreeDeeRenderManager manager = manager(dimension);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean first = new AtomicBoolean(true);
        Tile previous = new Tile(2,2,-64,320) {
            @Override public Terrain getTerrain(int x,int y) {
                if (first.compareAndSet(true,false)) {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return Terrain.STONE;
            }
        };
        dimension.addTile(previous);
        try {
            IsometricRenderParityTest.select(true); manager.renderTile(previous); assertTrue(entered.await(10,TimeUnit.SECONDS));
            dimension.removeTile(new java.awt.Point(2,2)); Tile replacement = new Tile(2,2,-64,320); dimension.addTile(replacement);
            manager.renderTile(replacement); release.countDown(); await(manager);
            Set<RenderResult> results = manager.getRenderedTiles(); assertEquals(1,results.size()); assertSame(replacement,results.iterator().next().getTile());
        } finally { release.countDown(); manager.stop(); }
    }
}
