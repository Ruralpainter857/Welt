package org.pepsoft.worldpainter;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import static org.junit.Assert.*;

/** Shared live water planes must remain independent under edits, history, transforms and old-reader semantics. */
public class TallWaterStorageTest {
    private String sharing,gen,procedural;
    @Before public void saveProperties() {
        sharing=System.getProperty("welt.tile.shareTallWater");gen=System.getProperty(Native.GEN_KEY);
        procedural=System.getProperty("welt.native.proceduralGeneration");
        System.setProperty("welt.tile.shareTallWater","true");System.setProperty("welt.native.proceduralGeneration","true");
    }
    @After public void restoreProperties() {
        restore("welt.tile.shareTallWater",sharing);restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",procedural);
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static HeightMapTileFactory factory(){return new HeightMapTileFactory(197,new ConstantHeightMap(75),-64,320,false,SimpleTheme.createDefault(Terrain.GRASS,-64,320,62,true,true));}
    private static short[] water(Tile tile) throws Exception {
        tile.getWaterLevel(0,0);Field field=Tile.class.getDeclaredField("tallWaterLevel");field.setAccessible(true);return(short[])field.get(tile);
    }
    private static Tile initialised(int x,int waterLevel,int maxHeight) {
        Tile tile=new Tile(x,0,-64,maxHeight);float[] heights=new float[16384];Arrays.fill(heights,75);
        tile.inhibitEvents();try{tile.initializeHeightAndWaterLevels(heights,waterLevel);}finally{tile.releaseEvents();}return tile;
    }

    @Test public void javaAndNativeCreationShareOnlyUniformWaterAndDetachOnEdit() throws Exception {
        for(boolean nativeMode:new boolean[]{false,true}) {
            System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));HeightMapTileFactory factory=factory();
            Tile a=factory.createTile(-1,0),b=factory.createTile(0,0);assertSame(water(a),water(b));
            a.setWaterLevel(3,7,123);assertEquals(123,a.getWaterLevel(3,7));assertEquals(62,a.getWaterLevel(4,7));
            assertEquals(62,b.getWaterLevel(3,7));assertNotSame(water(a),water(b));
            a.compactUniformWaterLevel();assertNotSame(water(a),water(b));
        }
    }

    @Test public void diagnosticFallbackNeverMakesExistingSharedPlanesWritable() throws Exception {
        Tile a=initialised(0,62,320),b=initialised(1,62,320);assertSame(water(a),water(b));
        System.setProperty("welt.tile.shareTallWater","false");a.setWaterLevel(0,0,70);
        assertEquals(62,b.getWaterLevel(0,0));assertEquals(62,a.getWaterLevel(1,0));
        Tile c=initialised(2,62,320),d=initialised(3,62,320);assertNotSame(water(c),water(d));
    }

    @Test public void unsignedLevelsAndDifferentOriginsPreserveEverySample() throws Exception {
        Tile a=initialised(0,65471,65472),b=initialised(1,65471,65472);assertSame(water(a),water(b));
        assertEquals(65471,a.getWaterLevel(127,127));a.setWaterLevel(0,0,-64);
        assertEquals(-64,a.getWaterLevel(0,0));assertEquals(65471,a.getWaterLevel(1,0));assertEquals(65471,b.getWaterLevel(0,0));
        Tile shifted=initialised(2,62,320);shifted.setMinMaxHeight(-128,384,HeightTransform.IDENTITY);
        assertEquals(62,shifted.getWaterLevel(0,0));assertEquals(65471,a.getWaterLevel(1,0));
    }

    @Test public void editsAndBulkReinitialisationKeepUndoRedoIndependent() throws Exception {
        Tile a=initialised(0,62,320),b=initialised(1,62,320);UndoManager undo=new UndoManager(8);a.register(undo);
        undo.armSavePoint();a.setWaterLevel(1,2,90);assertEquals(62,b.getWaterLevel(1,2));
        assertTrue(undo.undo());assertEquals(62,a.getWaterLevel(1,2));assertTrue(undo.redo());assertEquals(90,a.getWaterLevel(1,2));
        undo.armSavePoint();float[] heights=new float[16384];Arrays.fill(heights,78);
        a.inhibitEvents();try{a.initializeHeightAndWaterLevels(heights,70);}finally{a.releaseEvents();}
        assertEquals(70,a.getWaterLevel(1,2));assertTrue(undo.undo());assertEquals(90,a.getWaterLevel(1,2));
        assertTrue(undo.redo());assertEquals(70,a.getWaterLevel(1,2));assertEquals(62,b.getWaterLevel(1,2));
    }

    @Test public void transformedCopiesDetachWithoutChangingTheSource() throws Exception {
        Tile source=initialised(0,62,320),copy=source.transform(CoordinateTransform.NOOP);
        assertSame(water(source),water(copy));copy.setWaterLevel(4,5,90);assertEquals(62,source.getWaterLevel(4,5));
        Tile rotated=source.transform(CoordinateTransform.ROTATE_CLOCKWISE_90_DEGREES);
        rotated.setWaterLevel(7,9,80);assertEquals(62,source.getWaterLevel(7,9));assertEquals(62,rotated.getWaterLevel(8,9));
    }

    @Test public void serializationKeepsReadersWithoutInterningIndependent() throws Exception {
        Tile a=initialised(0,62,320),b=initialised(1,62,320);assertSame(water(a),water(b));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(new Tile[]{a,b});}
        assertSame(water(a),water(b));System.setProperty("welt.tile.shareTallWater","false");
        Tile[] restored;try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){restored=(Tile[])in.readObject();}
        assertNotSame(water(restored[0]),water(restored[1]));restored[0].setWaterLevel(2,3,99);
        assertEquals(62,restored[1].getWaterLevel(2,3));assertEquals(62,restored[0].getWaterLevel(3,3));
        System.setProperty("welt.tile.shareTallWater","true");
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){restored=(Tile[])in.readObject();}
        assertSame(water(restored[0]),water(restored[1]));restored[0].setWaterLevel(2,3,100);assertEquals(62,restored[1].getWaterLevel(2,3));
    }

    @Test public void evictedLivePlanesRemainReadOnlyAndNonuniformCandidatesStayPrivate() {
        UniformShortPlaneCache cache=new UniformShortPlaneCache(new short[8],2);
        short[] old=cache.uniform((short)1);cache.uniform((short)2);cache.uniform((short)3);
        assertTrue(cache.isShared(old));assertNotSame(old,cache.uniform((short)1));
        short[] varying={1,1,1,1,1,1,1,2};assertSame(varying,cache.intern(varying));assertFalse(cache.isShared(varying));
        assertSame(cache.uniform((short)-1),cache.intern(new short[]{-1,-1,-1,-1,-1,-1,-1,-1}));
    }

    @Test public void customTileSubclassesRetainPrivateNonzeroBuffers() throws Exception {
        Tile a=new PluginTile(0),b=new PluginTile(1);float[] heights=new float[16384];Arrays.fill(heights,75);
        for(Tile tile:new Tile[]{a,b}){tile.inhibitEvents();try{tile.initializeHeightAndWaterLevels(heights,62);}finally{tile.releaseEvents();}}
        assertNotSame(water(a),water(b));water(a)[0]=100;
        assertEquals(62,b.getWaterLevel(0,0));
    }
    private static final class PluginTile extends Tile { PluginTile(int x){super(x,0,-64,320);} }
}
