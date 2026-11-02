package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class MaterialFieldSnapshotTest {
    private MixedMaterial.Row[] rows(){return new MixedMaterial.Row[]{new MixedMaterial.Row(Material.STONE,2,.7f),new MixedMaterial.Row(Material.DIRT,3,1.2f)};}
    @Test public void snapshotsRetainDerivedThresholdsAndDoNotExpandLayeredPalettes() {
        MixedMaterial blobs=new MixedMaterial("Blobs",rows(),-1,null,1.3f);
        assertEquals(128,blobs.viewportModelBytes());ByteBuffer data=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(blobs.writeViewportModel(data,0));assertEquals(0x31464d57,data.getInt(0));assertEquals(2,data.getInt(8));
        assertEquals(Float.floatToIntBits(rows()[0].scale),Float.floatToIntBits(data.getFloat(96)));
        assertEquals(Float.floatToIntBits(blobs.getRows()[0].chance),Float.floatToIntBits(data.getFloat(100)));
        assertEquals(ColourScheme.DEFAULT.getColour(Material.STONE),data.getInt(104));
        MixedMaterial.Row[] many={new MixedMaterial.Row(Material.STONE,200000,1),new MixedMaterial.Row(Material.DIRT,300000,1)};
        MixedMaterial layered=new MixedMaterial("Strata",many,-1,null,new NoiseSettings(0,17,3,1.2f),-.7,.4,true);
        assertEquals(128,layered.viewportModelBytes());assertTrue(layered.writeViewportModel(data,0));
        assertEquals(3,data.getInt(8));assertEquals(500000,data.getInt(16));assertEquals(200000,data.getInt(108));assertEquals(300000,data.getInt(124));
        assertEquals(55904327L,data.getLong(80));assertEquals(-.7,data.getDouble(48),0);assertEquals(.4,data.getDouble(56),0);
        assertEquals(0,new MixedMaterial("Random",rows(),-1,null).viewportModelBytes());
        assertEquals(0,MixedMaterial.create("Simple",Material.STONE).viewportModelBytes());
    }
    @Test public void snapshotsRefreshEditsAndGuardDestinationBounds() {
        MixedMaterial material=new MixedMaterial("Live",rows(),-1,null,1.2f);
        ByteBuffer data=ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(material.writeViewportModel(data,16));assertFalse(material.writeViewportModel(data,17));
        material.edit("Changed",new MixedMaterial.Row[]{new MixedMaterial.Row(Material.SAND,2,.9f),new MixedMaterial.Row(Material.DIRT,3,1.7f)},-1,MixedMaterial.Mode.BLOBS,.4f,null,null,0,0,false);
        assertTrue(material.writeViewportModel(data,16));assertEquals(.4f,data.getFloat(40),0);
        assertEquals(ColourScheme.DEFAULT.getColour(Material.SAND),data.getInt(120));
    }
    @Test public void sampledFloatHeightsPreserveFractionsAndHistoricalVersions() {
        for(int[] bounds:new int[][]{{0,256},{-64,320},{-1024,3072}}) {
            Tile tile=new Tile(-3,7,bounds[0],bounds[1]);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setHeight(x,y,bounds[0]+((x*3+y*7)&127)+.25f);
            UndoManager undo=new UndoManager(4);tile.register(undo);
            try {
                for(int shift:new int[]{0,1,3,7})check(tile,shift);
                undo.armSavePoint();tile.setHeight(16,16,bounds[0]+17.75f);check(tile,0);assertTrue(undo.undo());check(tile,0);assertTrue(undo.redo());check(tile,0);
                assertThrows(IllegalArgumentException.class,()->tile.copySampledRenderHeights(8,FloatBuffer.allocate(1)));
                assertThrows(IllegalArgumentException.class,()->tile.copySampledRenderHeights(0,FloatBuffer.allocate(1)));
            }finally{tile.unregister();}
        }
    }
    private static void check(Tile tile,int shift) {
        int width=128>>shift;FloatBuffer values=FloatBuffer.allocate(width*width);tile.copySampledRenderHeights(shift,values);assertEquals(width*width,values.position());values.flip();
        for(int y=0;y<width;y++)for(int x=0;x<width;x++)assertEquals(Float.floatToIntBits(tile.getHeight(x<<shift,y<<shift)),Float.floatToIntBits(values.get()));
    }
}
