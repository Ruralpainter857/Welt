package org.pepsoft.worldpainter.objects;
import org.junit.Test;
import org.pepsoft.minecraft.Material;
import javax.vecmath.Point3i;
import java.io.*;
import java.util.Map;
import static org.junit.Assert.*;

public class GenericObjectCopyParityTest {
    private static GenericObject fixture(int x,int y,int z){
        Material[] blocks=new Material[x*y*z];
        for(int i=0;i<blocks.length;i++)blocks[i]=switch(i%3){case 0->null;case 1->Material.STONE;default->Material.AIR;};
        return new GenericObject("Non cubic",x,y,z,blocks,null,null,Map.of(WPObject.ATTRIBUTE_OFFSET.key,new Point3i(-1,-2,-3)));
    }
    private static void compare(WPObject source,WPObject copy){
        assertEquals(source.getName(),copy.getName());assertEquals(source.getDimensions(),copy.getDimensions());
        assertEquals(source.getAttributes(),copy.getAttributes());Point3i size=source.getDimensions();
        for(int z=0;z<size.z;z++)for(int y=0;y<size.y;y++)for(int x=0;x<size.x;x++){
            assertEquals(source.getMask(x,y,z),copy.getMask(x,y,z));assertEquals(source.getMaterial(x,y,z),copy.getMaterial(x,y,z));
        }
    }
    @Test public void copiesThreeDimensionsWithoutTruncationOrExcessStorage() throws Exception{
        var data=GenericObject.class.getDeclaredField("data");data.setAccessible(true);
        for(int[] shape:new int[][]{{2,3,5},{2,5,3},{1,1,7},{7,1,1},{1,7,1}}){
            GenericObject source=fixture(shape[0],shape[1],shape[2]),copy=new GenericObject(source);compare(source,copy);
            assertEquals(shape[0]*shape[1]*shape[2],((Material[])data.get(copy)).length);
            copy.setName("Copy");assertEquals("Non cubic",source.getName());
        }
    }
    @Test public void serializationAndClonePreserveCopiedMasksMaterialsAndAttributes() throws Exception{
        GenericObject copy=new GenericObject(fixture(2,3,5));compare(copy,copy.clone());var bytes=new ByteArrayOutputStream();
        try(var out=new ObjectOutputStream(bytes)){out.writeObject(copy);}
        try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){compare(copy,(WPObject)in.readObject());}
    }
}
