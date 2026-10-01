package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** One transaction covers the whole brush footprint, retaining global X/Y random order. */
final class ThemedHeightBrushAccess {
    private static final int MAX_BYTES=4*1024*1024;
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {ByteBuffer data;final Tile[] tiles=new Tile[9];}
    static boolean apply(Dimension dimension,int ox,int oy,int width,int height,float[] forces,int mode,float value,float low,float high) {
        if(width<=0||height<=0||width>256||height>256||forces==null||(long)width*height!=forces.length||mode<0||mode>1
                ||(long)ox+width-1>Integer.MAX_VALUE||(long)oy+height-1>Integer.MAX_VALUE
                ||!TileRegionAccess.canBatch(dimension,ox,oy,width,height)
                ||dimension.getTileFactory().getClass()!=HeightMapTileFactory.class)return false;
        HeightMapTileFactory factory=(HeightMapTileFactory)dimension.getTileFactory();
        if(factory.getTheme().getClass()!=SimpleTheme.class)return false;
        SimpleTheme.ImportPlan plan=((SimpleTheme)factory.getTheme()).prepareImport();if(plan==null)return false;
        Layer[] themeLayers=plan.getLayers(),layers=new Layer[themeLayers.length+3];
        System.arraycopy(themeLayers,0,layers,3,themeLayers.length);
        int n=layers.length,bytes=0;int[] kinds=new int[n],roles=new int[n],offsets=new int[n];
        for(int p=0;p<n;p++) {
            roles[p]=Math.min(p,3);Layer layer=layers[p];
            kinds[p]=p<2?0:p==2?1:layer.dataSize==Layer.DataSize.BYTE?1:layer.dataSize==Layer.DataSize.NIBBLE?2:
                    layer.dataSize==Layer.DataSize.BIT?3:4;
            offsets[p]=bytes;bytes+=SelectionCopyAccess.length(kinds[p]);
        }
        Scratch scratch=SCRATCH.get();Arrays.fill(scratch.tiles,null);try {int count=0;
        for(int tx=ox>>7;tx<=((ox+width-1)>>7);tx++)for(int ty=oy>>7;ty<=((oy+height-1)>>7);ty++) {
            Tile t=dimension.getTile(tx,ty);if(t!=null){if(t.getMinHeight()!=dimension.getMinHeight()||t.getMaxHeight()!=dimension.getMaxHeight())return false;scratch.tiles[count++]=t;}
        }
        int forceBase=128+n*16,themeBase=forceBase+forces.length*4,start=themeBase+plan.bytes(),step=288+bytes;
        long sizeLong=(long)start+(long)step*count;if(sizeLong>MAX_BYTES)return false;int size=(int)sizeLong;
        ByteBuffer d=scratch.data;if(d==null||d.capacity()<size){d=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);scratch.data=d;}
        d.clear().limit(size);for(int i=0;i<128;i+=8)d.putLong(i,0);
        d.putInt(0,0x42544857).putInt(4,1).putInt(8,size).putInt(12,n).putInt(16,count)
                .putInt(20,dimension.getMinHeight()).putInt(24,dimension.getMaxHeight()).putInt(28,mode).putFloat(32,value)
                .putFloat(36,low).putFloat(40,high).putInt(44,ox).putInt(48,oy).putInt(52,width).putInt(56,height)
                .putInt(60,forceBase).putInt(64,themeBase).putInt(68,start).putInt(72,step).putInt(76,Terrain.BEACHES.ordinal());
        for(int p=0;p<n;p++)d.putInt(128+p*16,kinds[p]).putInt(132+p*16,roles[p]).putInt(136+p*16,layers[p]==null?0:layers[p].getDefaultValue()).putInt(140+p*16,offsets[p]);
        d.position(forceBase);d.slice().order(d.order()).asFloatBuffer().put(forces);plan.write(d,themeBase,layers);
        for(int t=0;t<count;t++){int base=start+t*step;for(int i=0;i<256;i+=8)d.putLong(base+i,0);scratch.tiles[t].copySelectionPlanes(d,base+256,layers,roles,kinds,offsets);}
        d.position(0);if(!SimpleTheme.processThemedHeightBrush(d))return false;
        for(int t=0;t<count;t++) {
            int base=start+t*step;if(d.getLong(base+272)==0)continue;
            Tile tile=dimension.getTileForEditing(scratch.tiles[t].getX(),scratch.tiles[t].getY());
            d.position(base).limit(base+step);ByteBuffer tileFrame=d.slice().order(d.order());
            tile.applyOrderedPlanes(tileFrame,256,layers,roles,kinds,offsets);d.limit(size);
        }
        return true;
        }finally{Arrays.fill(scratch.tiles,null);}
    }
}
