package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.importing.HeightMapImporter;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;

/** Complete imports: sampling, conversion, tile creation, theme, notifications and JNI. */
public final class HeightMapImportBenchmark {
    static final Layer CHUNK = new Layer("welt.test.import.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) {};
    static HeightMap image(int side) {
        BufferedImage image = new BufferedImage(side*128, side*128, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.getRaster().setSample(x, y, 0, Math.floorMod(x*971+y*313+x*y*17, 65536));
        return BitmapHeightMap.build().withImage(image).now();
    }
    static SimpleTheme theme(int min, int max) {
        var ranges = new TreeMap<Integer, Terrain>(); ranges.put(min-1, Terrain.CUSTOM_1); ranges.put(50, Terrain.GRASS); ranges.put(100, Terrain.STONE);
        Map<Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x,y,z,l) -> Math.floorMod(z,16), Resources.INSTANCE);
        layers.put((x,y,z,l) -> z>80 ? 15 : 0, Biome.INSTANCE);
        layers.put((x,y,z,l) -> z<100 ? 0 : z<120 ? 7 : 15, Frost.INSTANCE);
        layers.put((x,y,z,l) -> z<80 ? 0 : z<90 ? 9 : 15, CHUNK);
        SimpleTheme t = new SimpleTheme(197, 62, ranges, layers, min, max, true, true);
        t.setDiscreteValues(Map.of(Biome.INSTANCE, 200)); return t;
    }
    static State fixture(HeightMap map, String mode, boolean voidBelow) {
        Platform platform = DefaultPlugin.JAVA_ANVIL_1_19; int min=platform.minZ, max=platform.standardMaxHeight;
        World2 world = new World2(platform, min, max);
        var factory = new HeightMapTileFactory(197, new ConstantHeightMap(62), min, max, false, theme(min,max));
        Dimension d = new Dimension(world, "Import", 197, factory, Dimension.Anchor.NORMAL_DETAIL, false);
        if (!mode.equals("fresh")) {
            var bounds = map.getExtent();
            for (int tx = bounds.x>>7; tx <= (bounds.x+bounds.width-1)>>7; tx++) for (int ty = bounds.y>>7; ty <= (bounds.y+bounds.height-1)>>7; ty++) {
                Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                    tile.setHeight(x,y,50+x%90+0.125f); tile.setWaterLevel(x,y,49); tile.setTerrain(x,y,Terrain.CUSTOM_2);
                }
                tile.releaseEvents(); d.addTile(tile);
            }
        }
        HeightMapImporter importer = new HeightMapImporter(); importer.setHeightMap(map); importer.setTileFactory(factory);
        importer.setMinHeight(min); importer.setMaxHeight(max); importer.setWorldLowLevel(min); importer.setWorldHighLevel(180);
        importer.setWorldWaterLevel(62); importer.setImageLowLevel(0); importer.setImageHighLevel(65535);
        importer.setOnlyRaise(mode.equals("raise")); importer.setVoidBelow(voidBelow); importer.setVoidBelowLevel(4096);
        return new State(d, importer, mode.equals("fresh"));
    }
    static Random random() throws Exception {
        var field = SimpleTheme.class.getDeclaredField("random"); field.setAccessible(true); return (Random) field.get(null);
    }
    record State(Dimension dimension, HeightMapImporter importer, boolean createTiles) {
        void run() throws Exception { importer.importToDimension(dimension, createTiles, null); }
    }
    public static void main(String[] args) throws Exception {
        Configuration previous = Configuration.getInstance(); String old = System.getProperty(Native.GEN_KEY);
        Configuration configuration = new Configuration(); configuration.setDefaultPlatform(DefaultPlugin.JAVA_ANVIL_1_19); Configuration.setInstance(configuration);
        try {
            boolean rust = args.length>0 && args[0].equals("rust"); String mode=args.length>1 ? args[1] : "fresh";
            int side=args.length>2 ? Integer.parseInt(args[2]) : 4; boolean voidBelow=args.length>3 && args[3].equals("void");
            NativeLoader.areSlicesAvailable(); HeightMap map=image(side);
            var bean=(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean(); double[] times=new double[7]; long[] bytes=new long[7]; long peak=0;
            for (int trial=-5; trial<7; trial++) {
                System.setProperty(Native.GEN_KEY,"false"); State state=fixture(map,mode,voidBelow); random().setSeed(42);
                System.setProperty(Native.GEN_KEY,Boolean.toString(rust));
                long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start=System.nanoTime(); state.run();
                double elapsed=(System.nanoTime()-start)/1e6; long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
                peak=Math.max(peak,NativeSlices.currentProcessResidentBytes()); if(trial>=0) {times[trial]=elapsed;bytes[trial]=allocated;}
            }
            Arrays.sort(times); Arrays.sort(bytes);
            System.out.printf(Locale.ROOT,"%s import_mode=%s side=%d void=%b median_ms=%.3f heap_allocated_bytes=%d sampled_peak_rss_bytes=%d%n",
                    rust?"Rust":"Java",mode,side*128,voidBelow,times[3],bytes[3],peak);
        } finally {Configuration.setInstance(previous);if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
}
