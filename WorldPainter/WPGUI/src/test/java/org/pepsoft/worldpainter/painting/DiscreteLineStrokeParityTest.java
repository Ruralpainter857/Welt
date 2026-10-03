package org.pepsoft.worldpainter.painting;

import java.util.Random;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class DiscreteLineStrokeParityTest {
    private static final class Defaults extends Layer {
        int fallback;
        Defaults(DataSize size,int fallback){super("welt.test.stroke.discrete."+size,"Discrete","",size,false,30);this.fallback=fallback;}
        @Override public int getDefaultValue(){return fallback;}
    }
    private static void same(Dimension left,Dimension right,Layer layer){
        for(Tile a:left.getTiles()){
            Tile b=right.getTile(a.getX(),a.getY());assertEquals(a.hasLayer(layer),b.hasLayer(layer));
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals(a.getLayerValue(layer,x,y),b.getLayerValue(layer,x,y));
        }
    }
    private static long draw(Dimension d,DiscreteLayerPaint paint,boolean nativeMode,boolean undo){
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));System.setProperty("welt.native.lineSetStroke","true");
        DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);painter.setUndo(undo);
        long before=LineStrokeAccess.completedCalls();d.setEventsInhibited(true);
        try{painter.drawLine(d,-190,-130,190,130,.91f,false);}finally{d.setEventsInhibited(false);}
        return LineStrokeAccess.completedCalls()-before;
    }
    @Test public void customDefaultsCapturedRemovalAndUndoRemainExact() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineSetStroke");
        Random random=LineSetStrokeBenchmark.mathRandom();
        try{
            for(Layer.DataSize size:new Layer.DataSize[]{Layer.DataSize.NIBBLE,Layer.DataSize.BYTE})
                for(boolean changeDefault:new boolean[]{false,true})for(boolean allocated:new boolean[]{false,true})
                    for(boolean remove:new boolean[]{false,true})for(boolean dither:new boolean[]{false,true}){
                        Defaults layer=new Defaults(size,size==Layer.DataSize.NIBBLE?3:255);
                        DiscreteLayerPaint paint=new DiscreteLayerPaint(layer,size.maxValue);
                        SymmetricBrush brush=SymmetricBrush.LINEAR_CIRCLE.clone();brush.setRadius(17);brush.setLevel(.63f);
                        paint.setBrush(brush);paint.setDither(dither);
                        if(changeDefault)layer.fallback=size==Layer.DataSize.NIBBLE?7:127;
                        Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture(),before=NibbleLayerPaintParityTest.fixture();
                        if(allocated){
                            byte[] values=new byte[16384];for(int i=0;i<values.length;i++)values[i]=(byte)(i%(size.maxValue+1));
                            for(Dimension d:new Dimension[]{expected,actual,before})for(Tile tile:d.getTiles()){
                                tile.inhibitEvents();tile.initializeLayerValues(layer,values);tile.releaseEvents();
                            }
                        }
                        UndoManager undo=new UndoManager();for(Tile tile:actual.getTiles())tile.register(undo);undo.armSavePoint();
                        random.setSeed(7331);draw(expected,paint,false,remove);long next=random.nextLong();
                        random.setSeed(7331);assertTrue(draw(actual,paint,true,remove)>0);assertEquals(next,random.nextLong());same(expected,actual,layer);
                        int target=remove?paint.getRemovalValue():paint.getValue();
                        if(!allocated&&target==layer.getDefaultValue()){assertFalse(undo.undo());same(before,actual,layer);}
                        else{assertTrue(undo.undo());same(before,actual,layer);assertTrue(undo.redo());same(expected,actual,layer);}
                    }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineSetStroke",flag);}
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}