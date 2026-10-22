package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.renderers.LayerRenderer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class CustomTerrainViewportParityTest {
    private MixedMaterial.Row[] rows() {
        return new MixedMaterial.Row[]{new MixedMaterial.Row(Material.STONE,600,1f),new MixedMaterial.Row(Material.SAND,400,0.7f)};
    }
    private MixedMaterial fixed(int colour) { return new MixedMaterial("Fixed custom colour",rows(),-1,colour); }
    private void requireLibrary() {
        boolean loaded=NativeLoader.areSlicesAvailable();
        if(Boolean.getBoolean("welt.test.requireCustomViewport"))assertTrue("Release JNI renderer required",loaded);
        org.junit.Assume.assumeTrue(loaded);
    }
    private Dimension fixture() {
        Dimension d=ViewportRenderParityTest.fixture(false,false);
        Terrain[] values={Terrain.CUSTOM_1,Terrain.CUSTOM_2,Terrain.CUSTOM_3,Terrain.GRASS};
        for(Tile tile:d.getTiles()) {
            tile.inhibitEvents();
            try {for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setTerrain(x,y,values[(x/16+y/16)&3]);}
            finally {tile.releaseEvents();}
        }
        return d;
    }
    private void bindings() {
        Terrain.setCustomMaterial(0,fixed(0x39715b));
        Terrain.setCustomMaterial(1,MixedMaterial.create("Custom single material",Material.get("welt:viewport_custom")));
        Terrain.setCustomMaterial(2,new MixedMaterial("Fixed layered colour",rows(),-1,0x8f2357a1,new NoiseSettings(0,17,3,1.2f),-0.7,0,true));
    }
    private static final class State implements AutoCloseable {
        private final MixedMaterial[] materials={Terrain.getCustomMaterial(0),Terrain.getCustomMaterial(1),Terrain.getCustomMaterial(2)};
        private final String gen=System.getProperty(Native.GEN_KEY),render=System.getProperty(Native.RENDER_KEY),flag=System.getProperty("welt.native.viewport");
        State(){System.setProperty(Native.GEN_KEY,"false");System.setProperty("welt.native.viewport","true");}
        public void close(){for(int i=0;i<3;i++)Terrain.setCustomMaterial(i,materials[i]);restore(Native.GEN_KEY,gen);restore(Native.RENDER_KEY,render);restore("welt.native.viewport",flag);}
        private void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    }
    private TileRenderer renderer(Dimension d,int zoom){return new TileRenderer(d,ColourScheme.DEFAULT,new CustomBiomeManager(),zoom,true,null);}
    private int[] draw(Dimension d,TileRenderer renderer,boolean nativeMode,int zoom){
        System.setProperty(Native.RENDER_KEY,Boolean.toString(nativeMode));
        int size=128 >> -zoom;BufferedImage image=new BufferedImage(size*2,size*2,BufferedImage.TYPE_INT_ARGB);
        for(Tile tile:d.getTiles())renderer.renderTile(tile,image,(tile.getX()+1)*size,(tile.getY()+1)*size);
        return ((DataBufferInt)image.getRaster().getDataBuffer()).getData();
    }
    private void compare(Dimension d,TileRenderer java,TileRenderer rust,int zoom,boolean supported){
        long before=rust.completedNativeViewportTiles();
        assertArrayEquals(draw(d,java,false,zoom),draw(d,rust,true,zoom));
        assertEquals(before+(supported?d.getTiles().size():0),rust.completedNativeViewportTiles());
    }
    @Test public void fixedColoursAndSimpleMaterialsMatchWithAllLightsZoomsAndAnchors(){
        requireLibrary();try(State ignored=new State()){
            bindings();Dimension d=fixture();
            for(int zoom:new int[]{0,-1,-3,-7})for(Dimension.LayerAnchor anchor:Dimension.LayerAnchor.values()){
                d.setTopLayerAnchor(anchor);
                TileRenderer java=renderer(d,zoom),rust=renderer(d,zoom);
                for(TileRenderer.LightOrigin light:TileRenderer.LightOrigin.values()){
                    java.setLightOrigin(light);rust.setLightOrigin(light);compare(d,java,rust,zoom,true);
                }
            }
        }
    }
    @Test public void replacingBindingsRefreshesPreviouslySupportedAndRejectedPaletteEntries() throws Exception {
        requireLibrary();try(State ignored=new State()){
            bindings();Dimension d=fixture();d.setTopLayerAnchor(Dimension.LayerAnchor.TERRAIN);
            TileRenderer java=renderer(d,0),rust=renderer(d,0);compare(d,java,rust,0,true);
            int[] previous=draw(d,rust,true,0);
            MixedMaterial edited=Terrain.getCustomMaterial(0);
            edited.edit("Edited in place",rows(),-1,MixedMaterial.Mode.NOISE,1f,0x851d67,null,0,0,false);
            assertSame(edited,Terrain.getCustomMaterial(0));compare(d,java,rust,0,true);
            assertFalse(Arrays.equals(previous,draw(d,rust,true,0)));
            previous=draw(d,rust,true,0);
            Terrain.setCustomMaterial(0,fixed(0xb13973));Terrain.setCustomMaterial(1,MixedMaterial.create("Changed simple",Material.SAND));
            compare(d,java,rust,0,true);assertFalse(Arrays.equals(previous,draw(d,rust,true,0)));
            MixedMaterial randomMaterial=new MixedMaterial("Uncoloured random",rows(),-1,null);Terrain.setCustomMaterial(0,randomMaterial);
            Field field=MixedMaterial.class.getDeclaredField("random");field.setAccessible(true);Random random=(Random)field.get(randomMaterial);
            random.setSeed(12345);int[] expected=draw(d,java,false,0);long next=random.nextLong();
            random.setSeed(12345);long before=rust.completedNativeViewportTiles();assertArrayEquals(expected,draw(d,rust,true,0));
            assertEquals(before,rust.completedNativeViewportTiles());assertEquals(next,random.nextLong());
            Terrain.setCustomMaterial(0,fixed(0x3719cb));compare(d,java,rust,0,true);
        }
    }
    @Test public void variableAndUnconfiguredTerrainsKeepTheOriginalRenderer(){
        requireLibrary();try(State ignored=new State()){
            bindings();Dimension d=fixture();d.setTopLayerAnchor(Dimension.LayerAnchor.BEDROCK);
            TileRenderer java=renderer(d,0),rust=renderer(d,0);
            Terrain.setCustomMaterial(0,new MixedMaterial("Variable blobs",rows(),-1,null,1.2f));compare(d,java,rust,0,false);
            Terrain.setCustomMaterial(0,null);compare(d,java,rust,0,false);
        }
    }
    @Test public void extremeHeightMetadataRejectsSingleMaterialBeforeWritingOutput(){
        requireLibrary();try(State ignored=new State()){
            Terrain.setCustomMaterial(0,MixedMaterial.create("Simple",Material.STONE));Dimension d=fixture();
            Tile tile=new Tile(-1,0,Integer.MAX_VALUE-1024,Integer.MAX_VALUE-512);
            byte[] terrains=new byte[16384];Arrays.fill(terrains,(byte)Terrain.CUSTOM_1.ordinal());
            int[] output=new int[16384];Arrays.fill(output,42);
            assertFalse(new ViewportRenderAccess(d.getWorld().getPlatform()).render(tile,d,new Layer[0],new LayerRenderer[0],new int[16384],new int[16384],terrains,
                false,10,0,false,false,false,false,false,0,0,0,0,0,output,0));
            for(int value:output)assertEquals(42,value);
        }
    }
}
