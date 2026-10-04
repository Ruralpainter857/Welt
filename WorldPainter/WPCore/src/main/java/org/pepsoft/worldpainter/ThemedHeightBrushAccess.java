package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.pepsoft.worldpainter.panels.EditorFilterPlan;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import static org.pepsoft.worldpainter.panels.EditorFilterPlan.*;
import static org.pepsoft.worldpainter.biomeschemes.Minecraft1_21Biomes.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** One transaction covers the whole brush footprint, retaining global X/Y random order. */
final class ThemedHeightBrushAccess {
    private static final int MAX_BYTES=4*1024*1024;
    private static final java.util.concurrent.atomic.AtomicLong COMPLETED=new java.util.concurrent.atomic.AtomicLong();
    static long completedCalls(){return COMPLETED.get();}
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {ByteBuffer data;final Tile[] tiles=new Tile[9];}
    /** Copy only the border: interior heights already belong to the shared packed tile planes. */
    private static void copyBorder(Dimension dimension,int ox,int oy,int width,int height,FloatBuffer out,int offset){
        for(int x=0;x<width;){
            int wx=ox+x,lx=wx&127,rw=Math.min(width-x,128-lx);
            for(int y=0;y<height;){
                int wy=oy+y,ly=wy&127,rh=Math.min(height-y,128-ly);Tile tile=dimension.getTile(wx>>7,wy>>7);
                if(tile!=null)tile.copyHeightRegionDirect(lx,ly,rw,rh,out,offset+x*height+y,height);
                else for(int dx=0;dx<rw;dx++)for(int dy=0;dy<rh;dy++)out.put(offset+(x+dx)*height+y+dy,-Float.MAX_VALUE);
                y+=rh;
            }
            x+=rw;
        }
    }
    private static final Layer[] HELPERS = {Biome.INSTANCE, FloodWithLava.INSTANCE, SelectionBlock.INSTANCE,
            SelectionChunk.INSTANCE, Annotations.INSTANCE, Frost.INSTANCE, River.INSTANCE,
            DeciduousForest.INSTANCE, PineForest.INSTANCE, SwampLand.INSTANCE, Jungle.INSTANCE};
    private static final int[] BIOMES = {BIOME_FROZEN_RIVER, BIOME_COLD_TAIGA, BIOME_FROZEN_OCEAN,
            BIOME_ICE_PLAINS, BIOME_RIVER, BIOME_SWAMPLAND, BIOME_JUNGLE, BIOME_OCEAN, BIOME_DEEP_OCEAN, BIOME_FOREST};
    private static final Terrain[] TERRAINS = Terrain.values();
    static boolean apply(Dimension dimension,int ox,int oy,int width,int height,float[] forces,int mode,float value,float low,float high) {
        return apply(dimension,ox,oy,width,height,forces,mode,value,low,high,null,1f);
    }
    static boolean apply(Dimension dimension,int ox,int oy,int width,int height,float[] forces,int mode,float value,
                         float low,float high,EditorFilterPlan filter,float dynamic) {
        if(width<=0||height<=0||width>256||height>256||forces==null||(long)width*height!=forces.length||mode<0||mode>5
                ||(long)ox+width-1>Integer.MAX_VALUE||(long)oy+height-1>Integer.MAX_VALUE
                ||!TileRegionAccess.canBatch(dimension,ox,oy,width,height)
                ||dimension.getTileFactory().getClass()!=HeightMapTileFactory.class)return false;
        if(mode==5&&(width>246||height>246||(long)ox-5<Integer.MIN_VALUE||(long)oy-5<Integer.MIN_VALUE
                ||(long)ox+width+4>Integer.MAX_VALUE||(long)oy+height+4>Integer.MAX_VALUE
                ||!TileRegionAccess.canBatch(dimension,ox-5,oy-5,width+10,height+10)))return false;
        if(filter!=null && (ox==Integer.MIN_VALUE||oy==Integer.MIN_VALUE
                ||(long)ox+width>Integer.MAX_VALUE||(long)oy+height>Integer.MAX_VALUE
                ||TERRAINS.length>256||!Float.isFinite(dynamic)||dynamic<0
                ||!TileRegionAccess.canBatch(dimension,ox-1,oy-1,width+2,height+2)))return false;
        HeightMapTileFactory factory=(HeightMapTileFactory)dimension.getTileFactory();
        if(factory.getTheme().getClass()!=SimpleTheme.class)return false;
        SimpleTheme.ImportPlan plan=((SimpleTheme)factory.getTheme()).prepareImport();if(plan==null)return false;
        Layer[] themeLayers=plan.getLayers(),layers=new Layer[themeLayers.length+3];
        System.arraycopy(themeLayers,0,layers,3,themeLayers.length);
        int[] helpers=new int[HELPERS.length],remap=null;
        Arrays.fill(helpers,-1);
        if(filter!=null){
            List<Layer> shared=new ArrayList<>(Arrays.asList(layers));
            remap=new int[filter.layers().size()];
            for(int i=0;i<remap.length;i++){
                Layer layer=filter.layers().get(i);int index=shared.indexOf(layer);
                if(index<0){index=shared.size();shared.add(layer);}remap[i]=index;
            }
            int deps=filter.dependencies();boolean auto=(deps&AUTO_BIOME)!=0;
            for(int i=0;i<helpers.length;i++){
                boolean needed=switch(i){case 0->(deps&BIOME)!=0;case 1->auto||(deps&LAVA)!=0;
                    case 2,3->(deps&SELECTION)!=0;case 4->(deps&ANNOTATIONS)!=0;default->auto;};
                if(needed){int index=shared.indexOf(HELPERS[i]);if(index<0){index=shared.size();shared.add(HELPERS[i]);}helpers[i]=index;}
            }
            layers=shared.toArray(Layer[]::new);
        }
        if(layers.length>64)return false;
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
        int header=filter==null?128:256;
        int forceBase=header+n*16,haloBase=forceBase+forces.length*4,haloCount=mode==5?10*(width+height+10):0;
        int borderBase=haloBase+haloCount*4,borderCount=filter==null?0:2*(width+height+2);
        int programBase=borderBase+borderCount*4,paletteBase=programBase+(filter==null?0:filter.encodedBytes());
        int themeBase=paletteBase+(filter==null?0:40+TERRAINS.length*8),start=themeBase+plan.bytes(),step=288+bytes;
        long sizeLong=(long)start+(long)step*count;if(sizeLong>MAX_BYTES)return false;int size=(int)sizeLong;
        ByteBuffer d=scratch.data;if(d==null||d.capacity()<size){d=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);scratch.data=d;}
        d.clear().limit(size);for(int i=0;i<header;i+=8)d.putLong(i,0);
        d.putInt(0,0x42544857).putInt(4,filter!=null?5:Boolean.getBoolean("welt.native.interleavedTheme")?4:mode==5?3:mode<2?1:2).putInt(8,size).putInt(12,n).putInt(16,count)
                .putInt(20,dimension.getMinHeight()).putInt(24,dimension.getMaxHeight()).putInt(28,mode).putFloat(32,value)
                .putFloat(36,low).putFloat(40,high).putInt(44,ox).putInt(48,oy).putInt(52,width).putInt(56,height)
                .putInt(60,forceBase).putInt(64,themeBase).putInt(68,start).putInt(72,step).putInt(76,Terrain.BEACHES.ordinal());
        if(mode==5){
            d.putInt(88,haloBase).putInt(92,haloCount);
            d.position(haloBase);FloatBuffer halo=d.slice().order(d.order()).asFloatBuffer();
            int left=5*(height+10),top=2*left,bottom=top+width*5;
            copyBorder(dimension,ox-5,oy-5,5,height+10,halo,0);
            copyBorder(dimension,ox+width,oy-5,5,height+10,halo,left);
            copyBorder(dimension,ox,oy-5,width,5,halo,top);
            copyBorder(dimension,ox,oy+height,width,5,halo,bottom);
        }
        if(filter!=null){
            d.putInt(96,programBase).putInt(100,filter.nodes().size()).putInt(104,paletteBase)
                    .putInt(108,TERRAINS.length).putInt(112,borderBase).putInt(116,borderCount)
                    .putFloat(120,dynamic).putInt(124,filter.dependencies())
                    .putInt(172,dimension.getAnchor().dim==-1?BIOME_HELL:dimension.getAnchor().dim==1?BIOME_SKY:-1)
                    .putInt(176,Terrain.WATER.ordinal()).putFloat(180,(float)Math.sqrt(8.0));
            for(int i=0;i<helpers.length;i++)d.putInt(128+i*4,helpers[i]);
            d.position(borderBase);FloatBuffer border=d.slice().order(d.order()).asFloatBuffer();
            int left=height+2,top=2*left,bottom=top+width;
            copyBorder(dimension,ox-1,oy-1,1,height+2,border,0);
            copyBorder(dimension,ox+width,oy-1,1,height+2,border,left);
            copyBorder(dimension,ox,oy-1,width,1,border,top);
            copyBorder(dimension,ox,oy+height,width,1,border,bottom);
            filter.writeTo(d,programBase,remap);
            for(int i=0;i<BIOMES.length;i++)d.putInt(paletteBase+i*4,BIOMES[i]);
            for(Terrain terrain:TERRAINS){
                int biome=terrain.isConfigured()?terrain.getDefaultBiome():-1;
                boolean forest=biome!=BIOME_DESERT&&biome!=BIOME_DESERT_HILLS&&biome!=BIOME_DESERT_M
                        &&biome!=BIOME_MESA&&biome!=BIOME_MESA_BRYCE&&biome!=BIOME_MESA_PLATEAU
                        &&biome!=BIOME_MESA_PLATEAU_F&&biome!=BIOME_MESA_PLATEAU_F_M&&biome!=BIOME_MESA_PLATEAU_M;
                d.putInt(paletteBase+40+terrain.ordinal()*8,biome).putInt(paletteBase+44+terrain.ordinal()*8,forest?1:0);
            }
        }
        for(int p=0;p<n;p++)d.putInt(header+p*16,kinds[p]).putInt(header+4+p*16,roles[p])
                .putInt(header+8+p*16,layers[p]==null?0:layers[p].getDefaultValue()).putInt(header+12+p*16,offsets[p]);
        d.position(forceBase);d.slice().order(d.order()).asFloatBuffer().put(forces);plan.write(d,themeBase,layers);
        for(int t=0;t<count;t++){int base=start+t*step;for(int i=0;i<256;i+=8)d.putLong(base+i,0);scratch.tiles[t].copySelectionPlanes(d,base+256,layers,roles,kinds,offsets);}
        d.position(0);if(!SimpleTheme.processThemedHeightBrush(d))return false;
        COMPLETED.incrementAndGet();
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
