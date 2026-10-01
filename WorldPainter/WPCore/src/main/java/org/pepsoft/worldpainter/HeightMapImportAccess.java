package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.importing.HeightMapImporter;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Void;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** One transaction per tile: samples, initial factory, relief, themes and grouped application. */
public final class HeightMapImportAccess {
    private static final int AREA = 16384, MAX_BYTES = 4 * 1024 * 1024;
    private static final ThreadLocal<ByteBuffer> BUFFER = new ThreadLocal<>();
    private final HeightMapImporter importer;
    private final HeightMapTileFactory factory;
    private final Rectangle extent;
    private final Layer[] layers;
    private final int[] kinds, roles, offsets;
    private final byte[] header, themes;
    private final int samples, initial, meta, size;
    private final boolean canCreate;
    private final BitmapImportSource source;

    private HeightMapImportAccess(HeightMapImporter importer, Dimension dimension, HeightMapTileFactory factory,
                                 SimpleTheme.ImportPlan first, SimpleTheme.ImportPlan second) {
        this.importer = importer; this.factory = factory;
        source = BitmapImportSource.prepare(importer.getHeightMap());
        extent = importer.getHeightMap().getExtent(); canCreate = first != null;
        List<Layer> planes = new ArrayList<>(Arrays.asList(null, null, null));
        if (importer.isVoidBelow()) planes.add(Void.INSTANCE);
        if (first != null) for (Layer layer : first.getLayers()) if (!planes.contains(layer)) planes.add(layer);
        if (second != null) for (Layer layer : second.getLayers()) if (!planes.contains(layer)) planes.add(layer);
        layers = planes.toArray(new Layer[0]); int n = layers.length;
        if (n > 64) throw new IllegalArgumentException("Too many import planes");
        kinds = new int[n]; roles = new int[n]; offsets = new int[n]; int bytes = 0;
        for (int p = 0; p < n; p++) {
            roles[p] = Math.min(p, 3);
            kinds[p] = p < 2 ? 0 : p == 2 ? 1 : layers[p].dataSize == Layer.DataSize.BYTE ? 1
                    : layers[p].dataSize == Layer.DataSize.NIBBLE ? 2 : layers[p].dataSize == Layer.DataSize.BIT ? 3 : 4;
            offsets[p] = bytes; bytes += SelectionCopyAccess.length(kinds[p]);
        }
        samples = 256+n*16; initial = samples+AREA*8;
        // Existing-tile actions omit initial samples and the factory theme.
        meta = initial+ (canCreate ? AREA*8 : 0);
        int firstBytes = first == null ? 0 : first.bytes(), secondBytes = second == null ? 0 : second.bytes();
        size = meta+32+bytes+firstBytes+secondBytes;
        if (size > MAX_BYTES) throw new IllegalArgumentException("Import buffers exceed the bounded ABI");
        header = new byte[samples]; ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0,0x4d494857).putInt(4,1).putInt(8,size).putInt(12,n)
                .putInt(16,importer.getMinHeight()).putInt(20,importer.getMaxHeight());
        HeightMap map = importer.getHeightMap();
        boolean unscaled = map instanceof BitmapHeightMap || map instanceof TransformingHeightMap
                && ((TransformingHeightMap)map).getScaleX()==1f && ((TransformingHeightMap)map).getScaleY()==1f
                && ((TransformingHeightMap)map).getBaseHeightMap() instanceof BitmapHeightMap;
        int flags = (importer.isOnlyRaise()?2:0) | (importer.isVoidBelow()?4:0)
                | (importer.getImageHighLevel()>=importer.getMaxHeight() && importer.getWorldHighLevel()<importer.getMaxHeight()?8:0)
                | (importer.getWorldLowLevel()==importer.getImageLowLevel() && importer.getWorldHighLevel()==importer.getImageHighLevel()?16:0)
                | (unscaled?0:32);
        h.putInt(24,flags).putInt(28,importer.getWorldWaterLevel()).putInt(32,planes.indexOf(Void.INSTANCE))
                .putInt(36,factory==null?0:factory.getWaterHeight()).putInt(48,extent.x).putInt(52,extent.y)
                .putInt(56,extent.width).putInt(60,extent.height).putDouble(64,importer.getImageLowLevel())
                .putDouble(72,(importer.getWorldHighLevel()-importer.getWorldLowLevel())
                        /(importer.getImageHighLevel()-importer.getImageLowLevel()))
                .putInt(80,importer.getWorldLowLevel()).putDouble(88,importer.getVoidBelowLevel()).putLong(96,dimension.getSeed())
                .putInt(112,samples).putInt(116,initial).putInt(120,meta).putInt(124,256)
                .putInt(128,first==null?0:meta+32+bytes).putInt(132,second==null?0:meta+32+bytes+firstBytes)
                .putInt(136,Terrain.BEACHES.ordinal());
        for (int p=0;p<n;p++) h.putInt(256+p*16,kinds[p]).putInt(260+p*16,roles[p])
                .putInt(264+p*16,layers[p]==null?0:layers[p].getDefaultValue()).putInt(268+p*16,offsets[p]);
        themes = new byte[firstBytes+secondBytes]; ByteBuffer t = ByteBuffer.wrap(themes).order(ByteOrder.LITTLE_ENDIAN);
        if (first!=null) first.write(t,0,layers);
        if (second!=null) second.write(t,firstBytes,layers);
    }

    /** Custom maps, themes and factories keep their original interleaved Java path. */
    public static HeightMapImportAccess prepare(HeightMapImporter importer, Dimension dimension, boolean fresh) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || !pure(importer.getHeightMap())
                || dimension.getMinHeight()!=importer.getMinHeight() || dimension.getMaxHeight()!=importer.getMaxHeight()) return null;
        SimpleTheme.ImportPlan first=null, second=null; HeightMapTileFactory factory=null;
        if (importer.getTheme()!=null) {
            if (importer.getTheme().getClass()!=SimpleTheme.class) return null;
            second=((SimpleTheme)importer.getTheme()).prepareImport(); if (second==null) return null;
        }
        if (fresh) {
            if (importer.getTileFactory().getClass()!=HeightMapTileFactory.class) return null;
            factory=(HeightMapTileFactory)importer.getTileFactory();
            if (factory.isFloodWithLava() || factory.getMinHeight()!=importer.getMinHeight()
                    || factory.getMaxHeight()!=importer.getMaxHeight() || !pure(factory.getHeightMap())
                    || factory.getTheme().getClass()!=SimpleTheme.class) return null;
            first=((SimpleTheme)factory.getTheme()).prepareImport(); if(first==null || !first.supportsFreshImport()) return null;
        }
        try {return new HeightMapImportAccess(importer,dimension,factory,first,second);}
        catch (IllegalArgumentException e) {return null;}
    }

    private static boolean pure(HeightMap map) {
        Class<?> type=map.getClass();
        if(type==BitmapHeightMap.class || type==ConstantHeightMap.class || type==NoiseHeightMap.class
                || type==MandelbrotHeightMap.class || type==NinePatchHeightMap.class) return true;
        if(type==BicubicHeightMap.class) return pure(((BicubicHeightMap)map).getHeightMap(0));
        if(type==TransformingHeightMap.class) return pure(((TransformingHeightMap)map).getBaseHeightMap());
        if(type==SumHeightMap.class || type==DifferenceHeightMap.class || type==ProductHeightMap.class
                || type==MinimisingHeightMap.class || type==MaximisingHeightMap.class) {
            CombiningHeightMap c=(CombiningHeightMap)map; return pure(c.getHeightMap1()) && pure(c.getHeightMap2());
        }
        return false;
    }

    /** A null return leaves the live tile and random stream unchanged. */
    public Tile importTile(Tile tile, int tx, int ty) {
        boolean fresh=tile==null;
        if(fresh!=canCreate || tile!=null && (tile.getClass()!=Tile.class || tile.getMinHeight()!=importer.getMinHeight()
                || tile.getMaxHeight()!=importer.getMaxHeight())) return null;
        long x=(long)tx*128,y=(long)ty*128;
        if(x<Integer.MIN_VALUE || y<Integer.MIN_VALUE || x+127>Integer.MAX_VALUE || y+127>Integer.MAX_VALUE) return null;
        BitmapImportSource.Window window = source == null ? null : source.window((int)x, (int)y);
        if (window != null && size + window.bytes() > MAX_BYTES) window = null;
        int frameSize = size + (window == null ? 0 : window.bytes());
        ByteBuffer d=BUFFER.get();
        if(d==null || d.capacity()<frameSize) {d=ByteBuffer.allocateDirect(frameSize).order(ByteOrder.LITTLE_ENDIAN);BUFFER.set(d);}
        d.clear().limit(frameSize); d.put(header);
        d.putInt(8, frameSize).putInt(4, window == null ? 1 : 2).putInt(208, window == null ? 0 : size); d.putInt(24,d.getInt(24)|(fresh?1:0)).putInt(40,(int)x).putInt(44,(int)y);
        for(int lx=0;lx<128;lx++) for(int ly=0;ly<128;ly++) {
            int i=lx+ly*128,wx=(int)x+lx,wy=(int)y+ly;
            if (window == null) d.putDouble(samples+i*8,extent.contains(wx,wy)?importer.getHeightMap().getHeight(wx,wy):0);
            if(fresh) d.putDouble(initial+i*8,factory.getHeightMap().getHeight(wx,wy));
        }
        if(fresh) tile=new Tile(tx,ty,importer.getMinHeight(),importer.getMaxHeight());
        tile.copySelectionPlanes(d,meta,layers,roles,kinds,offsets);
        d.position(size-themes.length);d.put(themes);d.position(0);
        if (window != null) source.write(d, size, window);
        if(!SimpleTheme.processHeightMapImport(d)) return null;
        tile.inhibitEvents();
        try {tile.applyOrderedPlanes(d,meta,layers,roles,kinds,offsets);}
        finally {tile.releaseEvents();}
        return tile;
    }
}
