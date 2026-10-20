package org.pepsoft.worldpainter.brushes;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

public class BitmapBrushParityTest {
    private void requireLibrary() {
        boolean available=NativeLoader.areSlicesAvailable();
        if(Boolean.getBoolean("welt.test.requireBitmapBrush"))assertTrue("Release JNI library required",available);
        org.junit.Assume.assumeTrue(available);
    }
    private void mode(boolean enabled) {
        System.setProperty(Native.GEN_KEY,Boolean.toString(enabled));
        System.setProperty("welt.native.bitmapBrush",Boolean.toString(enabled));
    }
    private void restore(String gen,String flag) {
        if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);
        if(flag==null)System.clearProperty("welt.native.bitmapBrush");else System.setProperty("welt.native.bitmapBrush",flag);
    }
    @Test public void everyStrengthMatchesJavaAcrossRadiusAndLevelChanges() throws Exception {
        requireLibrary();String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.bitmapBrush");
        try {
            byte[] png=BitmapBrushBenchmark.fixture();
            BitmapBrush java=new BitmapBrush(new ByteArrayInputStream(png),"Java bitmap");
            BitmapBrush nativeBrush=new BitmapBrush(new ByteArrayInputStream(png),"Native bitmap");
            for(int radius:new int[]{0,7,31,63,127,7}) {
                mode(false);java.setRadius(radius);mode(true);nativeBrush.setRadius(radius);
                for(float level:new float[]{0.375f,0.875f,0.1f,0f,-0.5f,Float.NEGATIVE_INFINITY,1f,1.5f,Float.NaN}) {
                    mode(false);java.setLevel(level);java.getStrength(0,0);
                    mode(true);nativeBrush.setLevel(level);
                    long before=BitmapBrush.nativeTransactions();nativeBrush.getStrength(0,0);
                    assertEquals(before+(radius>=32&&level<1?1:0),BitmapBrush.nativeTransactions());
                    for(int y=-radius;y<=radius;y++)for(int x=-radius;x<=radius;x++) {
                        assertEquals(Float.floatToIntBits(java.getStrength(x,y)),Float.floatToIntBits(nativeBrush.getStrength(x,y)));
                        assertEquals(Float.floatToIntBits(java.getFullStrength(x,y)),Float.floatToIntBits(nativeBrush.getFullStrength(x,y)));
                    }
                }
            }
        } finally {restore(gen,flag);}
    }
    @Test public void byteLookupMatchesRescaleOpForEveryPossibleInput() {
        requireLibrary();String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.bitmapBrush");
        try {
            mode(true);BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_BYTE_GRAY);
            byte[] source=((DataBufferByte)image.getRaster().getDataBuffer()).getData();
            for(int i=0;i<source.length;i++)source[i]=(byte)i;
            byte[] before=source.clone();
            for(float level:new float[]{0f,-0f,0.1f,0.375f,0.875f,Math.nextDown(1f),-3f,Float.NEGATIVE_INFINITY}) {
                BufferedImage java=new RescaleOp(level,0,null).filter(image,null);
                byte[] output=new byte[source.length];assertTrue(NativeSlices.scaleBitmapBrush(source,level,output));
                assertArrayEquals(((DataBufferByte)java.getRaster().getDataBuffer()).getData(),output);
                assertArrayEquals(before,source);
            }
        } finally {restore(gen,flag);}
    }
    @Test public void cloneAndCacheRecreationKeepTheOriginalIndependent() throws Exception {
        requireLibrary();String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.bitmapBrush");
        try {
            mode(true);BitmapBrush original=new BitmapBrush(new ByteArrayInputStream(BitmapBrushBenchmark.fixture()),"Original");
            original.setRadius(63);original.setLevel(0.375f);float initial=original.getStrength(0,0);
            Field reference=BitmapBrush.class.getDeclaredField("dataReference");reference.setAccessible(true);
            Object first=((Reference<?>)reference.get(original)).get();
            Field mask=first.getClass().getDeclaredField("fullStrengthMask");mask.setAccessible(true);
            Object full=mask.get(first);
            BitmapBrush clone=(BitmapBrush)original.clone();clone.setLevel(0.875f);clone.getStrength(0,0);
            assertEquals(Float.floatToIntBits(initial),Float.floatToIntBits(original.getStrength(0,0)));
            assertSame(full,mask.get(((Reference<?>)reference.get(clone)).get()));
            long before=BitmapBrush.nativeTransactions();
            for(int i=0;i<1000;i++)original.getStrength(0,0);
            assertEquals(before,BitmapBrush.nativeTransactions());
            ((Reference<?>)reference.get(original)).clear();original.getStrength(0,0);
            assertEquals(before+1,BitmapBrush.nativeTransactions());
            assertEquals(Float.floatToIntBits(initial),Float.floatToIntBits(original.getStrength(0,0)));
            assertNotSame(full,mask.get(((Reference<?>)reference.get(original)).get()));
            clone.setRadius(31);clone.getStrength(0,0);assertEquals(63,original.getRadius());
        } finally {restore(gen,flag);}
    }
    @Test public void invalidAndAliasedPlanesRemainUnchanged() {
        requireLibrary();String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.bitmapBrush");
        try {
            mode(true);byte[] source=new byte[4096],output=new byte[4096];Arrays.fill(source,(byte)73);Arrays.fill(output,(byte)42);
            byte[] before=output.clone();assertFalse(NativeSlices.scaleBitmapBrush(source,0.5f,source));
            assertFalse(NativeSlices.scaleBitmapBrush(source,Float.NaN,output));assertFalse(NativeSlices.scaleBitmapBrush(source,1f,output));
            assertFalse(NativeSlices.scaleBitmapBrush(new byte[4095],0.5f,output));assertArrayEquals(before,output);
            for(byte value:source)assertEquals(73,value);
        } finally {restore(gen,flag);}
    }
}
