package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Complete brush strokes reuse one compact plane per destination tile. */
public final class LineStrokeAccess {
    private LineStrokeAccess() { }
    private static final int[] ROLES={3}, KINDS={2}, OFFSETS={0};
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {
        ByteBuffer data; byte[] mask; final Layer[] layers=new Layer[1]; final int[] roles=new int[1],kinds=new int[1]; long calls;
    }
    public static boolean isEnabled() {return Boolean.parseBoolean(System.getProperty("welt.native.lineStroke", "true"));}
    public static boolean isSetEnabled() {return Boolean.parseBoolean(System.getProperty("welt.native.lineSetStroke", "true"));}
    /** Small strokes retain Java unless the caller explicitly selects the new transaction. */
    public static boolean useSetStroke(int width,int height,int x1,int y1,int x2,int y2) {
        String selected=System.getProperty("welt.native.lineSetStroke");
        if(selected!=null)return Boolean.parseBoolean(selected);
        long centers=Math.max(Math.abs((long)x2-x1),Math.abs((long)y2-y1))+1;
        return (long)width*height>=1089 && (long)width*height*centers>=500000;
    }
    public static long completedCalls() {return SCRATCH.get().calls;}

    private record Bounds(int x,int y,int width,int height) { }
    private static Bounds bounds(Dimension dimension,int x1,int y1,int x2,int y2,int bx,int by,int width,int height,
                                 float dynamic,boolean pixel,float[] strengths) {
        if(width<1 || height<1 || width>256 || height>256 || bx < -256 || bx > 256 || by < -256 || by > 256
                || strengths==null || strengths.length!=width*height || !Float.isFinite(dynamic) || dynamic<0 || dynamic>1
                || pixel && (bx!=0 || by!=0 || width!=1 || height!=1))return null;
        long points=Math.max(Math.abs((long)x2-x1),Math.abs((long)y2-y1))+1;
        long max=Math.max(Math.max(Math.abs((long)x1),Math.abs((long)x2)),Math.max(Math.abs((long)y1),Math.abs((long)y2)));
        if(points>65536 || max>1048576)return null;
        for(float strength:strengths)if(!Float.isFinite(strength)||strength<0||strength>1)return null;
        // Bound accumulation drift in Java's float line rasterizer before any writes or random draws.
        int halo=(int)Math.ceil(points*Math.ulp((float)(max+1)))+2;
        int ox=Math.min(x1,x2)+bx-halo,oy=Math.min(y1,y2)+by-halo;
        int rw=Math.abs(x2-x1)+width+2*halo,rh=Math.abs(y2-y1)+height+2*halo;
        long tiles=(long)(((ox+rw-1)>>7)-(ox>>7)+1)*(((oy+rh-1)>>7)-(oy>>7)+1);
        return tiles<=256 && TileRegionAccess.canBatch(dimension,ox,oy,rw,rh)?new Bounds(ox,oy,rw,rh):null;
    }

    /** Returns native tile calls, or -1 when the existing line painter must run. */
    public static int paint(Dimension dimension, Layer layer, int x1, int y1, int x2, int y2,
                            int bx, int by, int width, int height, float dynamic,
                            boolean undo, boolean pixel, float[] strengths) {
        if (!isEnabled() || layer==null || layer.dataSize!=Layer.DataSize.NIBBLE
                || layer.getDefaultValue()<0 || layer.getDefaultValue()>15)return -1;
        Bounds bounds=bounds(dimension,x1,y1,x2,y2,bx,by,width,height,dynamic,pixel,strengths);
        if(bounds==null)return -1;
        int ox=bounds.x,oy=bounds.y,rw=bounds.width,rh=bounds.height;
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

    /** Applies terrain, binary or discrete numeric lines; prepared dither selections survive a rejected JNI entry. */
    public static int paintSet(Dimension dimension,Terrain terrain,Layer layer,int target,int x1,int y1,int x2,int y2,
                               int bx,int by,int width,int height,float dynamic,boolean pixel,boolean dither,float[] strengths) {
        return paintSet(dimension,terrain,layer,target,x1,y1,x2,y2,bx,by,width,height,dynamic,pixel,dither,strengths,
                NativeSlices::paintLineStrokeTile);
    }
    static int paintSet(Dimension dimension,Terrain terrain,Layer layer,int target,int x1,int y1,int x2,int y2,
                        int bx,int by,int width,int height,float dynamic,boolean pixel,boolean dither,float[] strengths,
                        java.util.function.Predicate<ByteBuffer> nativeEdit) {
        if(!isEnabled() || !isSetEnabled())return -1;
        int kind, role, fallback=0;
        if (terrain!=null) {
            if (layer!=null || terrain.ordinal()>255) return -1;
            kind=1; role=2; target=terrain.ordinal();
        } else {
            if (layer==null) return -1;
            kind=switch (layer.dataSize) {
                case BYTE -> 1;
                case NIBBLE -> 2;
                case BIT -> 3;
                case BIT_PER_CHUNK -> 4;
                default -> -1;
            };
            if (kind<0) return -1;
            int max=kind==1?255:kind==2?15:1;
            fallback=kind<3?layer.getDefaultValue():0;
            if (target<0 || target>max || fallback<0 || fallback>max) return -1;
            role=3;
        }
        boolean numeric=role==3 && kind<3;
        Bounds b=bounds(dimension,x1,y1,x2,y2,bx,by,width,height,dynamic,pixel,strengths);if(b==null)return -1;
        dither=dither&&!pixel;
        int maskBytes=dither?(int)(((long)b.width*b.height+7)/8):0;
        int meta=256+strengths.length*4,planeBytes=SelectionCopyAccess.length(kind),maskOffset=meta+32+planeBytes,size=maskOffset+maskBytes;
        if(size>1024*1024)return -1;
        Scratch scratch=SCRATCH.get();scratch.layers[0]=layer;scratch.roles[0]=role;scratch.kinds[0]=kind;
        if(scratch.data==null || scratch.data.capacity()<size)scratch.data=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer data=scratch.data;data.clear().limit(size);for(int p=0;p<256;p+=8)data.putLong(p,0);
        data.putInt(0,0x50545357).putInt(4,numeric?3:2).putInt(8,size).putInt(12,x1).putInt(16,y1).putInt(20,x2).putInt(24,y2)
                .putInt(36,bx).putInt(40,by).putInt(44,width).putInt(48,height).putFloat(52,dynamic).putInt(60,fallback).putInt(64,meta)
                .putInt(68,pixel?1:0).putInt(72,kind).putInt(76,role).putInt(80,target).putInt(84,dither?1:0);
        for(int p=0;p<strengths.length;p++)data.putFloat(256+p*4,strengths[p]);
        if(dither){
            if(scratch.mask==null || scratch.mask.length<maskBytes)scratch.mask=new byte[maskBytes];
            java.util.Arrays.fill(scratch.mask,0,maskBytes,(byte)0);
            selectDither(dimension,x1,y1,x2,y2,bx,by,width,height,dynamic,strengths,b,scratch.mask);
            data.putInt(88,b.x).putInt(92,b.y).putInt(96,b.width).putInt(100,b.height).putInt(104,maskOffset).putInt(108,maskBytes);
            data.position(maskOffset);data.put(scratch.mask,0,maskBytes);
        }
        int calls=0;boolean nativeAvailable=true;
        for(int ty=b.y>>7;ty<=(b.y+b.height-1)>>7;ty++)for(int tx=b.x>>7;tx<=(b.x+b.width-1)>>7;tx++){
            Tile tile=dimension.getTileForEditing(tx,ty);if(tile==null)continue;
            data.putInt(28,tx).putInt(32,ty);tile.copySelectionPlanes(data,meta,scratch.layers,scratch.roles,scratch.kinds,OFFSETS);data.position(0);
            if(nativeAvailable && nativeEdit.test(data)){
                calls++;scratch.calls++;tile.applyOrderedPlanes(data,meta,scratch.layers,scratch.roles,scratch.kinds,OFFSETS);
            }else{
                if(!dither)return -1;
                // Random draws are already committed. Apply the same selected pixels without replaying Math.random.
                nativeAvailable=false;applyPreparedMask(tile,terrain,layer,target,b,scratch.mask);
            }
        }
        return calls;
    }
    private static void selectDither(Dimension dimension,int x1,int y1,int x2,int y2,int bx,int by,int width,int height,
                                      float dynamic,float[] strengths,Bounds bounds,byte[] mask){
        int dx=Math.abs(x2-x1),dy=Math.abs(y2-y1);
        if(dx<dy){
            if(y2<y1){int swap=x1;x1=x2;x2=swap;swap=y1;y1=y2;y2=swap;}
            float x=x1-.5f,step=(float)(x2-x1)/dy;
            for(int y=y1;y<=y2;y++){selectStamp(dimension,Math.round(x),y,bx,by,width,height,dynamic,strengths,bounds,mask);x+=step;}
        }else{
            if(x2<x1){int swap=x1;x1=x2;x2=swap;swap=y1;y1=y2;y2=swap;}
            float y=y1-.5f,step=(float)(y2-y1)/dx;
            for(int x=x1;x<=x2;x++){selectStamp(dimension,x,Math.round(y),bx,by,width,height,dynamic,strengths,bounds,mask);y+=step;}
        }
    }
    private static void selectStamp(Dimension dimension,int x,int y,int bx,int by,int width,int height,float dynamic,
                                     float[] strengths,Bounds bounds,byte[] mask){
        int ox=x+bx,oy=y+by;
        // A missing single-tile impression consumes no draws; a straddling impression still consumes every row.
        if(ox>>7==(ox+width-1)>>7 && oy>>7==(oy+height-1)>>7 && dimension.getTile(ox>>7,oy>>7)==null)return;
        for(int dy=0;dy<height;dy++)for(int dx=0;dx<width;dx++){
            float strength=dynamic*strengths[dx+dy*width];
            if(strength>.95f || Math.random()<strength){int p=ox+dx-bounds.x+(oy+dy-bounds.y)*bounds.width;mask[p>>3]|=(byte)(1<<(p&7));}
        }
    }
    private static void applyPreparedMask(Tile tile,Terrain terrain,Layer layer,int target,Bounds b,byte[] mask){
        int ox=tile.getX()*128,oy=tile.getY()*128;
        for(int y=Math.max(oy,b.y);y<Math.min(oy+128,b.y+b.height);y++)for(int x=Math.max(ox,b.x);x<Math.min(ox+128,b.x+b.width);x++){
            int p=x-b.x+(y-b.y)*b.width;if((mask[p>>3]&(1<<(p&7)))==0)continue;
            if(terrain!=null)tile.setTerrain(x-ox,y-oy,terrain);else if(layer.dataSize==Layer.DataSize.BIT || layer.dataSize==Layer.DataSize.BIT_PER_CHUNK)tile.setBitLayerValue(layer,x-ox,y-oy,target!=0);
            else tile.setLayerValue(layer,x-ox,y-oy,target);
        }
    }
}
