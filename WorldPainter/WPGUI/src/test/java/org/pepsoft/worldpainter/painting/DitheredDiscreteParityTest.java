package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Proxy;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class DitheredDiscreteParityTest {
    private static void ready(){org.junit.Assume.assumeTrue(Boolean.getBoolean("welt.test.requireDitheredDiscrete"));
        ByteBuffer frame=ByteBuffer.allocateDirect(48+16384+1).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0,0x4d424c57).putInt(4,1).putInt(16,1).putInt(20,1).putInt(24,8).putInt(28,128).put(48+16384,(byte)1);
        assertTrue("Packed Rust writer must execute",NativeSlices.editMaskedPlane(frame));assertEquals(1,frame.getInt(36));}
    private static DiscreteLayerPaint painter(Layer layer,DitheredDiscreteBenchmark.Draws random,int radius){
        var paint=new DiscreteLayerPaint(layer,layer==Biome.INSTANCE?42:12,random);var brush=SymmetricBrush.COSINE_CIRCLE.clone();
        brush.setRadius(radius);brush.setLevel(0.63f);paint.setBrush(brush);paint.setDither(true);return paint;
    }
    private static int[] events(Dimension dimension){int[] count={0};for(Tile tile:dimension.getTiles())tile.addListener((Tile.Listener)Proxy.newProxyInstance(
        Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(object,method,args)->{if(method.getName().equals("layerDataChanged"))count[0]++;return null;}));return count;}
    private static void same(Dimension a,Dimension b,Layer layer){assertEquals(a.getTileCount(),b.getTileCount());
        for(Tile tile:a.getTiles()){Tile other=b.getTile(tile.getX(),tile.getY());assertEquals(tile.hasLayer(layer),other.hasLayer(layer));
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){assertEquals(tile.getLayerValue(layer,x,y),other.getLayerValue(layer,x,y));
                assertEquals(tile.getRawHeight(x,y),other.getRawHeight(x,y));assertEquals(tile.getWaterLevel(x,y),other.getWaterLevel(x,y));}}
    }
    @Test public void wholeStrokesAndDrawCountsMatchOnBordersAndMissingTiles(){ready();
        for(Layer layer:new Layer[]{Biome.INSTANCE,Resources.INSTANCE})for(int radius:new int[]{0,3,63,127,511}){
            Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
            var left=painter(layer,ra,radius);var right=painter(layer,rb,radius);int[] ea=events(a),eb=events(b);
            for(int centre:new int[]{-1,64,127}){a.setEventsInhibited(true);b.setEventsInhibited(true);
                System.setProperty("welt.native.ditheredDiscrete","false");left.apply(a,centre,centre,0.74f);left.remove(a,centre,centre,0.74f);
                System.setProperty("welt.native.ditheredDiscrete","true");right.apply(b,centre,centre,0.74f);right.remove(b,centre,centre,0.74f);
                a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b,layer);assertEquals(ra.count,rb.count);assertArrayEquals(ea,eb);
                assertEquals(Double.doubleToLongBits(ra.getAsDouble()),Double.doubleToLongBits(rb.getAsDouble()));
            }
        }
    }
    @Test public void thresholdShortCircuitAndNanKeepTheExactDrawStream(){ready();
        for(float level:new float[]{0f,-1f,0.95f,Math.nextUp(0.95f),1f,Float.NaN,Float.POSITIVE_INFINITY}){
            Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
            var left=painter(Biome.INSTANCE,ra,3);var right=painter(Biome.INSTANCE,rb,3);a.setEventsInhibited(true);b.setEventsInhibited(true);
            System.setProperty("welt.native.ditheredDiscrete","false");left.apply(a,64,64,level);left.remove(a,64,64,level);
            System.setProperty("welt.native.ditheredDiscrete","true");right.apply(b,64,64,level);right.remove(b,64,64,level);
            a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b,Biome.INSTANCE);assertEquals(ra.count,rb.count);
        }
        Dimension absent=NibbleLayerPaintParityTest.fixture();var random=new DitheredDiscreteBenchmark.Draws();var p=painter(Biome.INSTANCE,random,3);
        absent.setEventsInhibited(true);p.apply(absent,-64,-64,1f);p.remove(absent,-64,-64,1f);absent.setEventsInhibited(false);assertEquals(0,random.count);
    }
    @Test public void filteredAndImmediateEventPathsRemainScalar(){ready();
        for(boolean filtered:new boolean[]{false,true}){
            Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
            var left=painter(Biome.INSTANCE,ra,3);var right=painter(Biome.INSTANCE,rb,3);int[] ea=events(a),eb=events(b);
            if(filtered){left.setFilter((x,y,strength)->a.getLayerValueAt(Biome.INSTANCE,x-1,y)==42?0.25f:strength);
                right.setFilter((x,y,strength)->b.getLayerValueAt(Biome.INSTANCE,x-1,y)==42?0.25f:strength);a.setEventsInhibited(true);b.setEventsInhibited(true);}
            System.setProperty("welt.native.ditheredDiscrete","false");left.apply(a,64,64,0.74f);
            System.setProperty("welt.native.ditheredDiscrete","true");right.apply(b,64,64,0.74f);
            if(filtered){a.setEventsInhibited(false);b.setEventsInhibited(false);}same(a,b,Biome.INSTANCE);assertEquals(ra.count,rb.count);assertArrayEquals(ea,eb);
        }
    }
    @Test public void undoRedoRestorePackedPlanes(){ready();Dimension world=NibbleLayerPaintParityTest.fixture();UndoManager undo=new UndoManager();
        for(Tile tile:world.getTiles())tile.register(undo);undo.armSavePoint();long before=DitheredDiscreteBenchmark.hash(world,Biome.INSTANCE);
        var random=new DitheredDiscreteBenchmark.Draws();var paint=painter(Biome.INSTANCE,random,127);
        System.setProperty("welt.native.ditheredDiscrete","true");world.setEventsInhibited(true);paint.apply(world,0,0,0.74f);world.setEventsInhibited(false);
        long after=DitheredDiscreteBenchmark.hash(world,Biome.INSTANCE);assertNotEquals(before,after);assertTrue(undo.undo());assertEquals(before,DitheredDiscreteBenchmark.hash(world,Biome.INSTANCE));
        assertTrue(undo.redo());assertEquals(after,DitheredDiscreteBenchmark.hash(world,Biome.INSTANCE));
    }
    @Test public void customTileCallbacksStayInterleavedWithDraws(){ready();
        var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
        java.util.List<Long> aTrace=new java.util.ArrayList<>(),bTrace=new java.util.ArrayList<>();
        Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();a.removeTile(0,0);b.removeTile(0,0);
        a.addTile(new Tile(0,0,-64,320){@Override public void setLayerValue(Layer layer,int x,int y,int value){aTrace.add(ra.count);super.setLayerValue(layer,x,y,value);}});
        b.addTile(new Tile(0,0,-64,320){@Override public void setLayerValue(Layer layer,int x,int y,int value){bTrace.add(rb.count);super.setLayerValue(layer,x,y,value);}});
        var left=painter(Biome.INSTANCE,ra,3);var right=painter(Biome.INSTANCE,rb,3);a.setEventsInhibited(true);b.setEventsInhibited(true);
        System.setProperty("welt.native.ditheredDiscrete","false");left.apply(a,64,64,0.74f);
        System.setProperty("welt.native.ditheredDiscrete","true");right.apply(b,64,64,0.74f);
        a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b,Biome.INSTANCE);assertEquals(aTrace,bTrace);assertEquals(ra.count,rb.count);
    }
    @Test public void customDefaultGetterKeepsScalarCallOrder(){ready();int[] calls={0};
        Layer custom=new Layer("Custom default", "Custom default",Layer.DataSize.NIBBLE,true,50){@Override public int getDefaultValue(){calls[0]++;return 3;}};
        Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
        var left=painter(custom,ra,3);var right=painter(custom,rb,3);a.setEventsInhibited(true);b.setEventsInhibited(true);
        calls[0]=0;System.setProperty("welt.native.ditheredDiscrete","false");left.apply(a,64,64,0.74f);int expected=calls[0];
        calls[0]=0;System.setProperty("welt.native.ditheredDiscrete","true");right.apply(b,64,64,0.74f);assertEquals(expected,calls[0]);
        a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b,custom);assertEquals(ra.count,rb.count);
    }
    @Test public void preparationFailureKeepsTheCompletedPixelPrefix(){ready();
        Dimension[] worlds={NibbleLayerPaintParityTest.fixture(),NibbleLayerPaintParityTest.fixture()};
        for(int mode=0;mode<2;mode++){var draws=new DitheredDiscreteBenchmark.Draws();
            var paint=new DiscreteLayerPaint(Biome.INSTANCE,42,()->{if(draws.count==27)throw new IllegalStateException("Source failed");return draws.getAsDouble();});
            var brush=SymmetricBrush.COSINE_CIRCLE.clone();brush.setRadius(3);brush.setLevel(0.63f);paint.setBrush(brush);paint.setDither(true);
            System.setProperty("welt.native.ditheredDiscrete",Boolean.toString(mode==1));worlds[mode].setEventsInhibited(true);
            try{paint.apply(worlds[mode],64,64,0.74f);fail("Source failure expected");}catch(IllegalStateException expected){assertEquals("Source failed",expected.getMessage());}
            finally{worlds[mode].setEventsInhibited(false);}assertEquals(27,draws.count);
        }same(worlds[0],worlds[1],Biome.INSTANCE);
    }
    @Test public void paddedMaskBandsMatchScalarCoordinates(){ready();Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();
        byte[] mask=new byte[512];int offset=7,stride=23;for(int y=0;y<13;y++)for(int x=0;x<17;x++)mask[offset+y*stride+x]=(byte)((x+y)%3==0?0:1);
        a.setEventsInhibited(true);b.setEventsInhibited(true);for(int y=0;y<13;y++)for(int x=0;x<17;x++)if(mask[offset+y*stride+x]!=0)a.setLayerValueAt(Biome.INSTANCE,120+x,123+y,42);
        MaskedPlaneAccess.applyLayer(b,Biome.INSTANCE,42,120,123,17,13,mask,offset,stride);a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b,Biome.INSTANCE);
    }
}