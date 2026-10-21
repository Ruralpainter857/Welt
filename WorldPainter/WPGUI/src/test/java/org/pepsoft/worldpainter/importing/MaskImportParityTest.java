package org.pepsoft.worldpainter.importing;

import java.io.File;
import java.util.*;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Frozen scalar importer provides an independent operation oracle. */
public class MaskImportParityTest {
    @Test public void importsMatchAcrossTargetsScalingRemovalAndMissingTiles()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            for(String type:new String[]{"terrain","terrain-index","discrete","fixed","bit","chunk","colour","direct","continuous"})
                for(float scale:new float[]{1,.75f,1.5f}) for(boolean remove:new boolean[]{false,true}) {
                    System.setProperty(Native.GEN_KEY,"false");var image=MaskImportBenchmark.image(type,139);
                    Dimension expected=MaskImportBenchmark.fixture(Math.round(139*scale),Math.round(139*scale),-31,-47);
                    Dimension actual=MaskImportBenchmark.fixture(Math.round(139*scale),Math.round(139*scale),-31,-47);
                    var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);MaskImportBenchmark.configure(scalar,type,scale,remove);
                    List<Float> wanted=new ArrayList<>(),observed=new ArrayList<>();scalar.doImport(progress(wanted));
                    System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);
                    MaskImportBenchmark.configure(importer,type,scale,remove);importer.doImport(progress(observed));
                    assertEquals(wanted,observed);same(expected,actual);
                    if(org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable())
                        assertEquals("Every supported tile must use JNI: "+type+" scale="+scale,actual.getTileCount(),importer.getLastNativeImportCalls());
                }
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
    @Test public void allNumericMappingsPreserveDefaultsUndoAndNotifications()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            for(int operation:new int[]{2,3,7,8,9,10,11}) {
                var layer=operation==2||operation==8||operation==10?org.pepsoft.worldpainter.layers.Biome.INSTANCE:
                        org.pepsoft.worldpainter.layers.Resources.INSTANCE;
                System.setProperty(Native.GEN_KEY,"false");var image=MaskImportBenchmark.image(operation==7?"direct":"continuous",139);
                Dimension before=MaskImportBenchmark.fixture(139,139,-31,-47),expected=MaskImportBenchmark.fixture(139,139,-31,-47),actual=MaskImportBenchmark.fixture(139,139,-31,-47);
                var undo=new org.pepsoft.util.undo.UndoManager();actual.registerUndoManager(undo);undo.armSavePoint();
                Map<String,Integer> wanted=events(expected),observed=events(actual);
                var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);
                scalar.setApplyToLayer(layer);scalar.setApplyToLayerValue(7);scalar.setScale(1);scalar.setxOffset(-31);scalar.setyOffset(-47);scalar.setRemoveExistingLayer(true);
                scalar.setMapping(numeric(operation,layer));scalar.doImport(null);
                System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);
                importer.setApplyToLayer(layer);importer.setApplyToLayerValue(7);importer.setScale(1);importer.setxOffset(-31);importer.setyOffset(-47);importer.setRemoveExistingLayer(true);
                importer.setMapping(numeric(operation,layer));importer.doImport(null);same(expected,actual);assertEquals(wanted,observed);
                assertTrue(undo.undo());same(before,actual);assertTrue(undo.redo());same(expected,actual);
            }
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
    @Test public void interiorRemovalPreservesAbsentPlanesAndPartialBorders()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            for(String type:new String[]{"continuous","bit","chunk","colour"}) {
                System.setProperty(Native.GEN_KEY,"false");var image=MaskImportBenchmark.image(type,512);
                if(type.equals("colour"))for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0);
                else for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.getRaster().setSample(x,y,0,0);
                Dimension expected=MaskImportBenchmark.fixture(512,512,-31,-47),actual=MaskImportBenchmark.fixture(512,512,-31,-47);
                var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);MaskImportBenchmark.configure(scalar,type,1,true);scalar.doImport(null);
                System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);MaskImportBenchmark.configure(importer,type,1,true);importer.doImport(null);
                same(expected,actual);assertFalse(actual.getTile(1,1).hasLayer(MaskImportBenchmark.layer(type)));
            }
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }

    private static Mapping numeric(int op,Layer layer) {
        return switch(op){case 2,3->Mapping.setLayerValue(layer,7);case 7->Mapping.mapToLayer(layer).threshold();
            case 8,9->Mapping.mapActualRangeToLayer(layer);default->Mapping.mapFullRangeToLayer(layer);};
    }
    @Test public void nestedDitherGatesKeepTheirStreamsAcrossRepeatedImports()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY,"false");var image=MaskImportBenchmark.image("bit",139);
            Dimension expected=MaskImportBenchmark.fixture(139,139,-31,-47),actual=MaskImportBenchmark.fixture(139,139,-31,-47);
            Mapping wanted=Mapping.mapToLayer(org.pepsoft.worldpainter.layers.Frost.INSTANCE).ditheredActualRange().ditheredFullRange().threshold();
            Mapping observed=Mapping.mapToLayer(org.pepsoft.worldpainter.layers.Frost.INSTANCE).ditheredActualRange().ditheredFullRange().threshold();
            for(int run=0;run<3;run++) {
                System.setProperty(Native.GEN_KEY,"false");var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);
                MaskImportBenchmark.configure(scalar,"bit",.75f,true);scalar.setMapping(wanted);scalar.doImport(null);
                System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);
                MaskImportBenchmark.configure(importer,"bit",.75f,true);importer.setMapping(observed);importer.doImport(null);same(expected,actual);
                if(org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable())assertTrue(importer.getLastNativeImportCalls()>0);
            }
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
    @Test public void customMappingsRetainScalarCallbackOrder()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY,"false");var image=MaskImportBenchmark.image("direct",139);
            Dimension expected=MaskImportBenchmark.fixture(139,139,-31,-47),actual=MaskImportBenchmark.fixture(139,139,-31,-47);
            List<String> wanted=new ArrayList<>(),observed=new ArrayList<>();
            var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);MaskImportBenchmark.configure(scalar,"direct",1,true);scalar.setMapping(recording(wanted));scalar.doImport(null);
            System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);MaskImportBenchmark.configure(importer,"direct",1,true);
            importer.setMapping(recording(observed));importer.doImport(null);same(expected,actual);assertEquals(wanted,observed);assertEquals(0,importer.getLastNativeImportCalls());
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
    @Test public void floatingMasksPreserveThresholdNanAndInfinityRules()throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {
            for(int format:new int[]{java.awt.image.DataBuffer.TYPE_FLOAT,java.awt.image.DataBuffer.TYPE_DOUBLE}) {
                int width=19,height=23;
                var model=new java.awt.image.ComponentSampleModel(format,width,height,1,width,new int[]{0});
                var raster=java.awt.image.Raster.createWritableRaster(model,model.createDataBuffer(),null);
                var colors=new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY),false,false,java.awt.Transparency.OPAQUE,format);
                var image=new java.awt.image.BufferedImage(colors,raster,false,null);
                double[] values={-0.0,0.0,-1,.49999999999999994,.5,.50000001,1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
                for(int y=0;y<height;y++)for(int x=0;x<width;x++)raster.setSample(x,y,0,values[(x+y*3)%values.length]);
                for(float scale:new float[]{1,1.5f}) {
                    System.setProperty(Native.GEN_KEY,"false");Dimension expected=MaskImportBenchmark.fixture(Math.round(width*scale),Math.round(height*scale),-31,-47),actual=MaskImportBenchmark.fixture(Math.round(width*scale),Math.round(height*scale),-31,-47);
                    var scalar=new ScalarMaskImporter(expected,new File("mask.png"),image);MaskImportBenchmark.configure(scalar,"chunk",scale,true);scalar.setThreshold(.5);scalar.doImport(null);
                    System.setProperty(Native.GEN_KEY,"true");var importer=new MaskImporter(actual,new File("mask.png"),image);MaskImportBenchmark.configure(importer,"chunk",scale,true);importer.setThreshold(.5);importer.doImport(null);
                    same(expected,actual);
                    if(org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable())assertTrue(importer.getLastNativeImportCalls()>0);
                }
            }
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }

    private static Mapping recording(List<String> output) {
        return new Mapping("Custom","Custom"){
            @Override void applyGreyScale(int x,int y,double value){output.add(tile.getX()+","+tile.getY()+":"+x+","+y);tile.setLayerValue(org.pepsoft.worldpainter.layers.Resources.INSTANCE,x,y,(int)value);}
        };
    }
    private static Map<String,Integer> events(Dimension dimension) {
        Map<String,Integer> result=new TreeMap<>();
        for(Tile tile:dimension.getTiles())tile.addListener((Tile.Listener)java.lang.reflect.Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(p,m,a)->{result.merge(tile.getX()+","+tile.getY()+":"+m.getName(),1,Integer::sum);return null;}));
        return result;
    }

    private static org.pepsoft.util.ProgressReceiver progress(List<Float> values) {
        return (org.pepsoft.util.ProgressReceiver)java.lang.reflect.Proxy.newProxyInstance(
                org.pepsoft.util.ProgressReceiver.class.getClassLoader(),new Class<?>[]{org.pepsoft.util.ProgressReceiver.class},
                (p,m,a)->{if(m.getName().equals("setProgress"))values.add((Float)a[0]);return null;});
    }
    static void same(Dimension expected,Dimension actual) {
        assertEquals(expected.getTileCoords(),actual.getTileCoords());
        for(Tile tile:expected.getTiles()) {
            Tile other=actual.getTile(tile.getX(),tile.getY());assertEquals(tile.getLayers(),other.getLayers());
            for(int x=0;x<128;x++)for(int y=0;y<128;y++) {
                assertEquals(tile.getRawHeight(x,y),other.getRawHeight(x,y));assertEquals(tile.getWaterLevel(x,y),other.getWaterLevel(x,y));
                assertEquals(tile.getTerrain(x,y),other.getTerrain(x,y));
                for(Layer layer:tile.getLayers()) {
                    if(layer.dataSize.maxValue==1)assertEquals(tile.getBitLayerValue(layer,x,y),other.getBitLayerValue(layer,x,y));
                    else assertEquals(tile.getLayerValue(layer,x,y),other.getLayerValue(layer,x,y));
                }
            }
        }
    }
}
