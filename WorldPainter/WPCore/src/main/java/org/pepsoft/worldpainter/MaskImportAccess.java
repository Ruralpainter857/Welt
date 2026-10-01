package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.nativeapi.SnapshotRandom;

/** One bounded transaction per tile: raster sampling, mapping, clearing and packed edits. */
public final class MaskImportAccess {
    private static final int MAX_BYTES = 4*1024*1024;
    private static final ThreadLocal<ByteBuffer> BUFFER = new ThreadLocal<>();
    private static final ThreadLocal<int[]> COLOURS = ThreadLocal.withInitial(() -> new int[16384]);

    /** A mapping explicitly opts into its equivalent native operation and ordered gates. */
    public static final class Plan {
        final Layer layer; final int operation, target; final int[] palette, gates;
        final SnapshotRandom[] randoms;
        public Plan(Layer layer, int operation, int target, int[] palette) {
            this(layer,operation,target,palette,new int[0],new SnapshotRandom[0]);
        }
        private Plan(Layer layer,int operation,int target,int[] palette,int[] gates,SnapshotRandom[] randoms) {
            this.layer=layer;this.operation=operation;this.target=target;this.palette=palette;this.gates=gates;this.randoms=randoms;
        }
        public Plan gated(int gate,SnapshotRandom random) {
            if(gates.length==8)return null;
            int[] next=new int[gates.length+1];SnapshotRandom[] streams=new SnapshotRandom[next.length];
            next[0]=gate;streams[0]=random;System.arraycopy(gates,0,next,1,gates.length);
            System.arraycopy(randoms,0,streams,1,randoms.length);
            return new Plan(layer,operation,target,palette,next,streams);
        }
    }
    private final Plan plan;
    private final BitmapImportSource source;
    private final BufferedImage colours;
    private final boolean discrete,clamp;
    private final double low,high,max,threshold;
    private final Layer[] layers;
    private final int[] roles,kinds,offsets={0};
    private final int bytes,defaults;

    private MaskImportAccess(Plan plan,BitmapImportSource source,BufferedImage colours,boolean discrete,boolean clamp,
                             double low,double high,double max,double threshold) {
        this.plan=plan;this.source=source;this.colours=colours;this.discrete=discrete;this.clamp=clamp;
        this.low=low;this.high=high;this.max=max;this.threshold=threshold;
        Layer layer=plan.layer;
        int kind=layer==null?1:layer.dataSize==Layer.DataSize.BYTE?1:layer.dataSize==Layer.DataSize.NIBBLE?2:
                layer.dataSize==Layer.DataSize.BIT?3:layer.dataSize==Layer.DataSize.BIT_PER_CHUNK?4:-1;
        if(kind<0)throw new IllegalArgumentException("Unsupported mask plane");
        layers=new Layer[]{layer};roles=new int[]{layer==null?2:3};kinds=new int[]{kind};
        defaults=layer==null||kind>=3?0:layer.getDefaultValue();bytes=SelectionCopyAccess.length(kind);
        if(defaults<0||defaults>(kind==1?255:kind==2?15:1))throw new IllegalArgumentException("Invalid default");
    }
    public static MaskImportAccess prepare(Plan plan,Layer removalLayer,HeightMap sampling,BufferedImage colours,
                                            boolean discrete,boolean clamp,double low,double high,double max,double threshold) {
        if(plan==null||plan.operation<0||plan.operation>12||!Native.isGenEnabled()||!NativeLoader.areSlicesAvailable()||plan.layer!=removalLayer)return null;
        if((plan.operation==4||plan.operation==12)&&(plan.palette==null||plan.palette.length==0
                ||plan.palette.length>(plan.operation==12?4096:256)))return null;
        for(int g=0;g<plan.gates.length;g++)if(plan.gates[g]<1||plan.gates[g]>3||plan.gates[g]!=1&&plan.randoms[g]==null)return null;
        BitmapImportSource source=colours==null?BitmapImportSource.prepare(sampling):null;
        if(colours==null&&source==null||colours!=null&&colours.getClass()!=BufferedImage.class)return null;
        try{return new MaskImportAccess(plan,source,colours,discrete,clamp,low,high,max,threshold);}
        catch(IllegalArgumentException e){return null;}
    }
    /** Rejected transactions leave the tile and every gate random stream untouched. */
    public boolean apply(Tile tile,int imageX,int imageY,int width,int height,boolean remove,boolean interior) {
        if(tile.getClass()!=Tile.class)return false;
        long ix2=(long)imageX+127,iy2=(long)imageY+127;
        if(ix2>Integer.MAX_VALUE||iy2>Integer.MAX_VALUE)return false;
        int lx=(int)Math.max(0,-(long)imageX),ly=(int)Math.max(0,-(long)imageY);
        int rw=(int)Math.min(128L-lx,(long)width-imageX-lx),rh=(int)Math.min(128L-ly,(long)height-imageY-ly);
        if(lx>127||ly>127||rw<=0||rh<=0)return true;
        BitmapImportSource.Window window=source==null?null:source.window(imageX,imageY);
        if(source!=null&&window==null)return false;
        int gates=256,palette=plan.operation==12||plan.operation==4?gates+plan.gates.length*16:0;
        int paletteBytes=palette==0?0:plan.palette.length;
        int pixels=gates+plan.gates.length*16+paletteBytes,meta=pixels+(colours==null?0:rw*rh*4);
        int sourceBase=meta+32+bytes,size=sourceBase+(window==null?0:window.bytes());
        if(size>MAX_BYTES)return false;
        ByteBuffer d=BUFFER.get();
        if(d==null||d.capacity()<size){d=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);BUFFER.set(d);}
        d.clear().limit(size);for(int i=0;i<256;i+=8)d.putLong(i,0);
        boolean clear=remove&&plan.layer!=null;
        int flags=(clear?(interior?2:1):0)|(discrete?4:0)|(clamp?16:0)|(colours==null?0:32);
        d.putInt(0,0x4d494d57).putInt(4,1).putInt(8,size).putInt(12,kinds[0]).putInt(16,roles[0])
                .putInt(20,plan.operation).putInt(24,plan.target).putInt(28,defaults).putInt(32,flags)
                .putInt(36,lx).putInt(40,ly).putInt(44,rw).putInt(48,rh).putInt(52,imageX).putInt(56,imageY)
                .putInt(60,plan.layer==null?Terrain.values().length-1:plan.layer.dataSize.maxValue)
                .putInt(64,paletteBytes).putDouble(72,low).putDouble(80,high).putDouble(88,max).putDouble(96,threshold)
                .putInt(104,plan.gates.length).putInt(108,gates).putInt(112,palette)
                .putInt(116,colours==null?0:pixels).putInt(120,window==null?0:sourceBase).putInt(124,meta);
        for(int g=0;g<plan.gates.length;g++)d.putInt(gates+g*16,plan.gates[g]).putInt(gates+g*16+4,0);
        if(palette!=0){if(plan.palette==null||plan.palette.length==0)return false;for(int i=0;i<paletteBytes;i++)d.put(palette+i,(byte)plan.palette[i]);}
        if(colours!=null){int[] values=COLOURS.get();colours.getRGB(imageX+lx,imageY+ly,rw,rh,values,0,rw);
            d.position(pixels);d.slice().order(d.order()).asIntBuffer().put(values,0,rw*rh);}
        else source.write(d,sourceBase,window);
        tile.copySelectionPlanes(d,meta,layers,roles,kinds,offsets);d.position(0);
        if(!process(d,0,gates))return false;
        if(clear&&interior)tile.clearLayerData(plan.layer);
        tile.applySelectionPlanes(d,meta,layers,roles,kinds,offsets);return true;
    }
    private boolean process(ByteBuffer data,int gate,int gates) {
        if(gate==plan.randoms.length)return NativeSlices.importMaskTile(data);
        SnapshotRandom stream=plan.randoms[gate];
        if(stream==null){data.putLong(gates+gate*16+8,0);return process(data,gate+1,gates);}
        synchronized(stream){
            data.putLong(gates+gate*16+8,stream.snapshotState());
            if(!process(data,gate+1,gates))return false;
            stream.restoreState(data.getLong(gates+gate*16+8));return true;
        }
    }
}
