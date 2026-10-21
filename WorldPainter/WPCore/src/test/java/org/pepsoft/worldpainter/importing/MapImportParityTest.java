package org.pepsoft.worldpainter.importing;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.pepsoft.minecraft.RegionHeaderAccess;
import org.pepsoft.minecraft.ChunkTagReader;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.exporting.JavaChunkStore;
import org.pepsoft.worldpainter.layers.Layer;
import static org.junit.Assert.*;

public class MapImportParityTest extends AbstractTool {
    private static final String[] KEYS={"user.home","org.pepsoft.worldpainter.threads","welt.native.chunkNbt","welt.native.chunkNbtKernel","welt.native.regionHeader","welt.native.regionHeaderKernel","welt.profile.mapImport","welt.benchmark.importReadOnly","welt.native.mapSurface","welt.packedArrayCube.compactPaletteStorage","welt.native.mapSurfacePacked","welt.import.packedSections"};
    private static final String[] SAVED=new String[KEYS.length];
    private static Configuration previousConfiguration;
    private static File fixture;
    @BeforeClass public static void prepare() throws Exception {
        previousConfiguration=Configuration.getInstance();for(int i=0;i<KEYS.length;i++)SAVED[i]=System.getProperty(KEYS[i]);
        Path root=Files.createTempDirectory("welt-import-parity-");Files.createDirectories(root.resolve("home"));System.setProperty("user.home",root.resolve("home").toString());
        System.setProperty("org.pepsoft.worldpainter.threads","1");initialisePlatform();fixture=MapImportBenchmark.fixture(root);
    }
    @AfterClass public static void restore(){for(int i=0;i<KEYS.length;i++){if(SAVED[i]==null)System.clearProperty(KEYS[i]);else System.setProperty(KEYS[i],SAVED[i]);}Configuration.setInstance(previousConfiguration);}
    private static void same(World2 expected,World2 actual){
        assertEquals(expected.getName(),actual.getName());assertEquals(expected.getPlatform(),actual.getPlatform());
        Dimension left=expected.getDimension(Dimension.Anchor.NORMAL_DETAIL),right=actual.getDimension(Dimension.Anchor.NORMAL_DETAIL);
        assertEquals(left.getMinHeight(),right.getMinHeight());assertEquals(left.getMaxHeight(),right.getMaxHeight());assertEquals(left.getTileCount(),right.getTileCount());
        for(Tile a:MapImportBenchmark.tiles(expected)){
            Tile b=right.getTile(a.getX(),a.getY());assertNotNull(b);assertEquals(a.getLayers(),b.getLayers());List<Layer> layers=MapImportBenchmark.layers(a);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                assertEquals(Float.floatToIntBits(a.getHeight(x,y)),Float.floatToIntBits(b.getHeight(x,y)));assertEquals(a.getTerrain(x,y),b.getTerrain(x,y));assertEquals(a.getWaterLevel(x,y),b.getWaterLevel(x,y));
                for(Layer layer:layers)assertEquals(MapImportBenchmark.value(a,layer,x,y),MapImportBenchmark.value(b,layer,x,y));
            }
        }
    }
    @Test public void wholeImportsMatchAcrossReadOnlyModesAndRegionWorkers() throws Exception {
        System.setProperty("welt.profile.mapImport","false");
        for(String workers:new String[]{"1","4"})for(MapImporter.ReadOnlyOption readOnly:MapImporter.ReadOnlyOption.values()){
            System.setProperty("org.pepsoft.worldpainter.threads",workers);System.setProperty("welt.benchmark.importReadOnly",readOnly.name());
            System.setProperty("welt.native.chunkNbt","false");System.setProperty("welt.native.regionHeader","false");World2 expected=MapImportBenchmark.importWorld(fixture);
            System.setProperty("welt.native.chunkNbt","true");System.setProperty("welt.native.chunkNbtKernel","true");long nbtCalls=ChunkTagReader.completedCalls();System.setProperty("welt.native.regionHeader","true");System.setProperty("welt.native.regionHeaderKernel","true");long calls=RegionHeaderAccess.completedCalls();World2 actual=MapImportBenchmark.importWorld(fixture);
            assertTrue(RegionHeaderAccess.completedCalls()>calls);assertEquals(16,ChunkTagReader.completedCalls()-nbtCalls);same(expected,actual);
        }
    }
    @Test public void nativeSurfacesMatchAllReadOnlyModesAndWorkerCounts() throws Exception {
        System.setProperty("welt.native.chunkNbt","false");
        System.setProperty("welt.native.regionHeader","false");
        System.setProperty("welt.packedArrayCube.compactPaletteStorage","true");
        MapImportBenchmark.quietLogging();
        for(String workers:new String[]{"1","4"})for(MapImporter.ReadOnlyOption option:MapImporter.ReadOnlyOption.values()){
            System.setProperty("org.pepsoft.worldpainter.threads",workers);
            System.setProperty("welt.benchmark.importReadOnly",option.name());
            System.setProperty("welt.native.mapSurface","false");
            World2 expected=MapImportBenchmark.importWorld(fixture);
            long calls=org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls();
            System.setProperty("welt.native.mapSurface","true");
            World2 actual=MapImportBenchmark.importWorld(fixture);
            assertEquals(16,org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls()-calls);
            same(expected,actual);
        }
    }
    @Test public void packedNbtSurfacesAndTheirJavaFallbackMatchCompleteImports() throws Exception {
        System.setProperty("welt.native.chunkNbt","false");System.setProperty("welt.native.regionHeader","false");
        System.setProperty("welt.packedArrayCube.compactPaletteStorage","false");
        System.setProperty("welt.native.mapSurface","false");
        MapImportBenchmark.quietLogging();
        for(String workers:new String[]{"1","4"})for(MapImporter.ReadOnlyOption option:MapImporter.ReadOnlyOption.values()) {
            System.setProperty("org.pepsoft.worldpainter.threads",workers);System.setProperty("welt.benchmark.importReadOnly",option.name());
            System.setProperty("welt.native.mapSurfacePacked","false");System.setProperty("welt.import.packedSections","false");
            World2 expected=MapImportBenchmark.importWorld(fixture);
            long calls=org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls();
            System.setProperty("welt.native.mapSurfacePacked","true");
            World2 actual=MapImportBenchmark.importWorld(fixture);
            assertEquals(16,org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls()-calls);same(expected,actual);
            System.setProperty("welt.native.mapSurfacePacked","false");System.setProperty("welt.import.packedSections","true");
            calls=org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls();
            same(expected,MapImportBenchmark.importWorld(fixture));
            assertEquals(calls,org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls());
        }
        System.setProperty("welt.import.packedSections","false");
    }
    @Test public void objectStorageKeepsTheJavaSurfaceFallback() throws Exception {
        System.setProperty("welt.native.chunkNbt","false");
        System.setProperty("welt.native.regionHeader","false");
        System.setProperty("welt.packedArrayCube.compactPaletteStorage","false");
        System.setProperty("welt.native.mapSurface","false");
        World2 expected=MapImportBenchmark.importWorld(fixture);
        long calls=org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls();
        System.setProperty("welt.native.mapSurface","true");
        World2 actual=MapImportBenchmark.importWorld(fixture);
        assertEquals(calls,org.pepsoft.minecraft.ChunkSurfaceAccess.completedCalls());
        same(expected,actual);
    }
    @Test public void diagnosticsAndGroupedJavaFallbackPreserveTheWholeImportedWorld() throws Exception {
        System.setProperty("org.pepsoft.worldpainter.threads","1");System.setProperty("welt.benchmark.importReadOnly","MAN_MADE");System.setProperty("welt.native.regionHeader","false");System.setProperty("welt.profile.mapImport","false");
        World2 expected=MapImportBenchmark.importWorld(fixture);assertEquals(0,JavaMapImporter.importProfile().chunks());assertEquals(0,JavaChunkStore.decodeProfile().chunks());
        System.setProperty("welt.native.regionHeader","true");System.setProperty("welt.native.regionHeaderKernel","false");System.setProperty("welt.profile.mapImport","true");long calls=RegionHeaderAccess.completedCalls();
        World2 actual=MapImportBenchmark.importWorld(fixture);assertEquals(calls,RegionHeaderAccess.completedCalls());same(expected,actual);
        assertEquals(16,JavaMapImporter.importProfile().chunks());assertEquals(16,JavaChunkStore.decodeProfile().chunks());assertTrue(JavaChunkStore.decodeProfile().allocatedBytes()>0);
    }
}