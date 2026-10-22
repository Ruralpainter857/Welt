package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import static org.junit.Assert.*;

public class SlopeHeightMapCloneTest {
    @Test public void clonePreservesScalingNameSeedAndSampledHeights() {
        for(float scale:new float[]{1f,1.75f,3.7f,-2f}) {
            SlopeHeightMap original=new SlopeHeightMap(new NoiseHeightMap(45,.9,2,63),scale,"Scaled slope");
            original.setSeed(-89173L);
            SlopeHeightMap copy=original.clone();
            assertEquals(Float.floatToIntBits(scale),Float.floatToIntBits(copy.getVerticalScaling()));
            assertEquals(original.getName(),copy.getName());assertEquals(original.getSeed(),copy.getSeed());
            assertNotSame(original.getBaseHeightMap(),copy.getBaseHeightMap());
            for(int y=-7;y<=7;y++)for(int x=-7;x<=7;x++) {
                assertEquals(Double.doubleToLongBits(original.getHeight(x,y)),Double.doubleToLongBits(copy.getHeight(x,y)));
            }
            copy.setVerticalScaling(5f);
            assertEquals(Float.floatToIntBits(scale),Float.floatToIntBits(original.getVerticalScaling()));
        }
    }
    @Test public void cloningACompositionRetainsNestedSlopeSettings() {
        HeightMap original=new SumHeightMap(new ConstantHeightMap(64),new ProductHeightMap(
                new SlopeHeightMap(new NoiseHeightMap(45,.9,2,63),3.7f),new ConstantHeightMap(.8)));
        original.setSeed(17);
        HeightMap copy=original.clone();
        for(int y=-7;y<=7;y++)for(int x=-7;x<=7;x++) {
            assertEquals(Double.doubleToLongBits(original.getHeight(x,y)),Double.doubleToLongBits(copy.getHeight(x,y)));
        }
    }
}
