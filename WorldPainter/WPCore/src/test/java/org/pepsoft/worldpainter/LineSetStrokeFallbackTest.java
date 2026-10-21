package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

/** A rejected older ABI must reuse the prepared mask instead of replaying random draws. */
public class LineSetStrokeFallbackTest {
    private static final Layer CHUNK=new Layer("welt.test.set.fallback", "Fallback", "", Layer.DataSize.BIT_PER_CHUNK,false,30) { };
    private static Random random() throws Exception {
        var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)f.get(null);
        var stream=Class.forName("java.lang.Math$RandomNumberGeneratorHolder").getDeclaredField("randomNumberGenerator");
        return (Random)unsafe.getObject(unsafe.staticFieldBase(stream),unsafe.staticFieldOffset(stream));
    }
    private static Dimension fixture(){
        World2 world=new World2(org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL,0,256);
        Dimension d=new Dimension(world,"Surface",7331,new HeightMapTileFactory(7331,new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(64),0,256,false,org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS,0,256,64)),Dimension.Anchor.NORMAL_DETAIL);
        for(int y=-2;y<=1;y++)for(int x=-2;x<=1;x++)if(x!=-1||y!=-1){Tile tile=new Tile(x,y,0,256);d.addTile(tile);}
        return d;
    }
    private static int draw(Dimension d,Terrain terrain,Layer layer,int target,Predicate<ByteBuffer> edit,float[] strengths){
        d.setEventsInhibited(true);
        try{return LineStrokeAccess.paintSet(d,terrain,layer,target,-190,-130,190,130,-16,-16,33,33,1,false,true,strengths,edit);}
        finally{d.setEventsInhibited(false);}
    }
    private static void same(Dimension expected,Dimension actual,Layer layer){
        for(Tile a:expected.getTiles()){
            Tile b=actual.getTile(a.getX(),a.getY());assertEquals(a.getLayers(),b.getLayers());
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                assertEquals(a.getTerrain(x,y),b.getTerrain(x,y));
                if(layer!=null){if(layer.dataSize==Layer.DataSize.BIT || layer.dataSize==Layer.DataSize.BIT_PER_CHUNK)assertEquals(a.getBitLayerValue(layer,x,y),b.getBitLayerValue(layer,x,y));
                    else assertEquals(a.getLayerValue(layer,x,y),b.getLayerValue(layer,x,y));}
            }
        }
    }
    @Test public void rejectedEntryAndPartialSuccessKeepThePreparedSelectionAndRandomStream() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),set=System.getProperty("welt.native.lineSetStroke"),line=System.getProperty("welt.native.lineStroke");
        try{
            System.setProperty(Native.GEN_KEY,"true");System.setProperty("welt.native.lineSetStroke","true");System.setProperty("welt.native.lineStroke","true");
            float[] strengths=new float[33*33];for(int i=0;i<strengths.length;i++)strengths[i]=i%3==0?0:i%3==1?.4f:.97f;
            Random random=random();
            for(Layer layer:new Layer[]{null,Frost.INSTANCE,CHUNK,org.pepsoft.worldpainter.layers.Biome.INSTANCE,org.pepsoft.worldpainter.layers.Annotations.INSTANCE})for(int target:layer==org.pepsoft.worldpainter.layers.Biome.INSTANCE?new int[]{0,254,255}:layer==org.pepsoft.worldpainter.layers.Annotations.INSTANCE?new int[]{0,12,15}:new int[]{0,1})for(int successes:new int[]{0,1}){
                Terrain terrain=layer==null?Terrain.CUSTOM_3:null;Dimension expected=fixture(),actual=fixture();
                random.setSeed(44);assertTrue(draw(expected,terrain,layer,target,NativeSlices::paintLineStrokeTile,strengths)>1);long next=random.nextLong();
                AtomicInteger attempts=new AtomicInteger();
                random.setSeed(44);assertEquals(successes,draw(actual,terrain,layer,target,data->attempts.getAndIncrement()<successes&&NativeSlices.paintLineStrokeTile(data),strengths));
                assertEquals(successes+1,attempts.get());assertEquals(next,random.nextLong());same(expected,actual,layer);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineSetStroke",set);restore("welt.native.lineStroke",line);}
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}