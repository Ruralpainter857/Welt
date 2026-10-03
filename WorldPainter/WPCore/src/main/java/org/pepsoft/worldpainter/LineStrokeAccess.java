package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Complete monotone nibble-layer strokes reuse one compact plane per destination tile. */
public final class LineStrokeAccess {
    private LineStrokeAccess() { }
    private static final int[] ROLES={3}, KINDS={2}, OFFSETS={0};
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {ByteBuffer data; final Layer[] layers=new Layer[1];long calls;}
    public static boolean isEnabled() {return Boolean.parseBoolean(System.getProperty("welt.native.lineStroke", "true"));}
    public static long completedCalls() {return SCRATCH.get().calls;}

    /** Returns native tile calls, or -1 when the existing line painter must run. */
    public static int paint(Dimension dimension, Layer layer, int x1, int y1, int x2, int y2,
                            int bx, int by, int width, int height, float dynamic,
                            boolean undo, boolean pixel, float[] strengths) {
        if (!isEnabled() || layer == null || layer.dataSize!=Layer.DataSize.NIBBLE
                || layer.getDefaultValue()<0 || layer.getDefaultValue()>15 || width<1 || height<1 || width>256 || height>256
                || bx < -256 || bx > 256 || by < -256 || by > 256 || strengths.length != width*height
                || !Float.isFinite(dynamic) || dynamic<0 || dynamic>1
                || pixel && (bx!=0 || by!=0 || width!=1 || height!=1)) return -1;
        long points=Math.max(Math.abs((long)x2-x1),Math.abs((long)y2-y1))+1;
        long max=Math.max(Math.max(Math.abs((long)x1),Math.abs((long)x2)),Math.max(Math.abs((long)y1),Math.abs((long)y2)));
        if(points>65536 || max>1048576) return -1;
        for(float strength:strengths)if(!Float.isFinite(strength)||strength<0||strength>1)return -1;
        // Bound accumulation drift in Java's float line rasterizer before any writes.
        int halo=(int)Math.ceil(points*Math.ulp((float)(max+1)))+2;
        int ox=Math.min(x1,x2)+bx-halo,oy=Math.min(y1,y2)+by-halo;
        int rw=Math.abs(x2-x1)+width+2*halo,rh=Math.abs(y2-y1)+height+2*halo;
        long tileCount=(long)(((ox+rw-1)>>7)-(ox>>7)+1)*(((oy+rh-1)>>7)-(oy>>7)+1);
        if(tileCount>256 || !TileRegionAccess.canBatch(dimension,ox,oy,rw,rh))return -1;
        int meta=256+strengths.length*4,size=meta+32+8192;
        Scratch scratch=SCRATCH.get();scratch.layers[0]=layer;
        if(scratch.data==null || scratch.data.capacity()<size)scratch.data=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer d=scratch.data;d.clear().limit(size);
        for(int p=0;p<256;p+=8)d.putLong(p,0);
        d.putInt(0,0x50545357).putInt(4,1).putInt(8,size).putInt(12,x1).putInt(16,y1).putInt(20,x2).putInt(24,y2)
                .putInt(36,bx).putInt(40,by).putInt(44,width).putInt(48,height).putFloat(52,dynamic)
                .putInt(56,undo?1:0).putInt(60,layer.getDefaultValue()).putInt(64,meta).putInt(68,pixel?1:0);
        for(int p=0;p<strengths.length;p++)d.putFloat(256+p*4,strengths[p]);
        int calls=0;
        for(int ty=oy>>7;ty<=(oy+rh-1)>>7;ty++)for(int tx=ox>>7;tx<=(ox+rw-1)>>7;tx++){
            Tile tile=dimension.getTileForEditing(tx,ty);if(tile==null)continue;
            d.putInt(28,tx).putInt(32,ty);tile.copySelectionPlanes(d,meta,scratch.layers,ROLES,KINDS,OFFSETS);d.position(0);
            // Max/min edits are idempotent, so a missing older DLL can replay the original stroke.
            if(!NativeSlices.paintLineStrokeTile(d))return -1;
            calls++;scratch.calls++;tile.applyOrderedPlanes(d,meta,scratch.layers,ROLES,KINDS,OFFSETS);
        }
        return calls;
    }
}
