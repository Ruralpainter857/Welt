package org.pepsoft.worldpainter;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.worldpainter.nativeapi.Native;
/** Complete filtered height strokes include evolving slope reads and grouped application. */
public final class FilteredHeightBrushBenchmark {
    static Dimension fixture(){
        Dimension d=ThemedHeightBrushBenchmark.fixture();
        for(Tile t:d.getTiles()){t.inhibitEvents();try{for(int x=0;x<128;x++)for(int y=0;y<128;y++){
            t.setRawHeight(x,y,t.getRawHeight(x,y)+256*100);t.setWaterLevel(x,y,90);
            t.setLayerValue(org.pepsoft.worldpainter.layers.Resources.INSTANCE,x,y,8);
            t.setLayerValue(org.pepsoft.worldpainter.layers.DeciduousForest.INSTANCE,x,y,8);
            t.setBitLayerValue(org.pepsoft.worldpainter.selection.SelectionChunk.INSTANCE,x,y,(x/16+y/16)%2==0);
        }}finally{t.releaseEvents();}}
        return d;
    }
    static DefaultFilter filter(Dimension d){return new DefaultFilter(d,false,false,68,110,true,true,Terrain.GRASS,false,null,30,false);}
    static void scalar(Dimension d,int ox,int oy,int side,float[] forces,DefaultFilter filter,int mode,float value,float low,float high){
        for(int x=0;x<side;x++)for(int y=0;y<side;y++){
            int wx=ox+x,wy=oy+y;float current=d.getHeightAt(wx,wy),target=mode==0?Math.min(current+value,high):mode==1?Math.max(current-value,low):value;
            float force=filter.modifyStrength(wx,wy,forces[y*side+x]);
            if(force>0){float edited=force*target+(1-force)*current;if(mode==2||(mode==0||mode==3?edited>current:edited<current))d.setHeightAt(wx,wy,edited);}
        }
    }
    private record Result(double ms,long allocated,Dimension d,long calls){}
    private static Result run(boolean rust,int radius){
        Dimension d=fixture();DefaultFilter f=filter(d);var plan=EditorFilterPlan.compile(f,d);int side=2*radius+1,origin=-radius/2;float[] xmajor=new float[side*side],row=new float[side*side];
        long changes=d.getChangeNo(),calls=FilteredPaintAccess.completedTransactions();var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long a=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        for(int stroke=0;stroke<8;stroke++){
            int operation=Integer.getInteger("welt.benchmark.filteredHeightMode",-1);if(operation<0)operation=stroke%2;if(operation>4)throw new IllegalArgumentException("Invalid height mode");float value=operation<2?8:stroke%2==0?85.125f:110.5f;
            HeightBrushBenchmark.strengths(xmajor,radius,stroke);for(int x=0;x<side;x++)for(int y=0;y<side;y++)row[y*side+x]=xmajor[x*side+y];
            d.setEventsInhibited(true);try{
                if(rust){long before=FilteredPaintAccess.completedTransactions();if(!FilteredPaintAccess.applyHeight(d,plan,origin,origin,side,side,row,operation,value,-64,319)||FilteredPaintAccess.completedTransactions()!=before+1)throw new AssertionError("Grouped filtered height unavailable");}
                else scalar(d,origin,origin,side,row,f,operation,value,-64,319);
            }finally{d.setEventsInhibited(false);}
        }
        double ms=(System.nanoTime()-start)/1e6;long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-a;
        if(d.getChangeNo()==changes)throw new AssertionError("Fixture did not edit heights");
        return new Result(ms,allocated,d,FilteredPaintAccess.completedTransactions()-calls);
    }
    public static void main(String[] args){
        System.setProperty(Native.GEN_KEY,"true");String mode=args.length==0?"java":args[0];int radius=Integer.getInteger("welt.benchmark.filteredHeightRadius",64),warm=Integer.getInteger("welt.benchmark.filteredHeightWarmups",20),trials=9;
        double[] j=new double[trials],r=new double[trials],ratios=new double[trials];long[] ja=new long[trials],ra=new long[trials];
        for(int i=-warm;i<trials;i++){
            if(mode.equals("compare")){Result a=null,b=null;for(int pass=0;pass<2;pass++){if(((i+pass)&1)==0)a=run(false,radius);else b=run(true,radius);}if(a.calls!=0||b.calls!=8)throw new AssertionError("Grouped call count mismatch");ThemedFlattenBrushBenchmark.same(a.d,b.d);if(i>=0){j[i]=a.ms;r[i]=b.ms;ratios[i]=a.ms/b.ms;ja[i]=a.allocated;ra[i]=b.allocated;}}
            else{Result a=run(mode.equals("rust"),radius);if(i>=0){j[i]=a.ms;ja[i]=a.allocated;}}
        }
        Arrays.sort(j);Arrays.sort(r);Arrays.sort(ratios);Arrays.sort(ja);Arrays.sort(ra);
        if(mode.equals("compare"))System.out.printf(Locale.ROOT,"filteredHeight radius=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f allocated=%d rustAllocated=%d%n",radius,j[4],r[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4]);
        else System.out.printf(Locale.ROOT,"filteredHeight radius=%d mode=%s medianMs=%.3f allocated=%d%n",radius,mode,j[4],ja[4]);
    }
}
