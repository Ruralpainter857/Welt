package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.MaskedPlaneAccess;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.brushes.*;
import org.pepsoft.worldpainter.layers.Layer;
import java.util.function.DoubleSupplier;
import static org.pepsoft.worldpainter.Constants.*;

/** One bounded mask preparer shared by painters; each owner retains its own scratch. */
final class DitheredBrushBatch {
    private byte[] mask;
    boolean apply(Dimension dimension, Brush brush, DoubleSupplier random, Layer layer, Terrain terrain, int target,
                  int centreX, int centreY, float dynamicLevel, int x1, int y1, int x2, int y2,
                  boolean oneTile, boolean fullStrength) {
        long width=(long)x2-x1+1,height=(long)y2-y1+1;
        if (!(brush instanceof SymmetricBrush || brush instanceof BitmapBrush || brush instanceof RotatedBrush)
                || width<1 || height<1 || width>1024 || height>1024 || width*height>1_048_576
                || !(terrain!=null?MaskedPlaneAccess.canBatchTerrain(dimension,terrain,x1,y1,(int)width,(int)height)
                    :MaskedPlaneAccess.canBatchLayer(dimension,layer,target,x1,y1,(int)width,(int)height))) return false;
        // Preserve the original single-tile editable access even when no point is selected.
        if(oneTile && dimension.getTileForEditing(x1>>TILE_SIZE_BITS,y1>>TILE_SIZE_BITS)==null)return true;
        int capacity=(int)width*Math.min(128,(int)height);
        if(mask==null||mask.length<capacity)mask=new byte[capacity];
        for(int row=0;row<height;){
            int originY=y1+row,rows=Math.min((int)height-row,128-(originY&TILE_SIZE_MASK)),index=0;
            try{
                for(int dy=0;dy<rows;dy++)for(int dx=0;dx<width;dx++){
                    float strength=dynamicLevel*(fullStrength?brush.getFullStrength(x1+dx-centreX,originY+dy-centreY)
                            :brush.getStrength(x1+dx-centreX,originY+dy-centreY));
                    byte selected=(byte)(strength>0.95f||random.getAsDouble()<strength?1:0);mask[index++]=selected;
                }
            }catch(RuntimeException failure){
                java.util.Arrays.fill(mask,index,rows*(int)width,(byte)0);
                if(index>0)write(dimension,layer,terrain,target,x1,originY,(int)width,rows);
                throw failure;
            }
            write(dimension,layer,terrain,target,x1,originY,(int)width,rows);row+=rows;
        }
        return true;
    }
    private void write(Dimension dimension,Layer layer,Terrain terrain,int target,int x,int y,int width,int height){
        if(terrain!=null)MaskedPlaneAccess.applyTerrain(dimension,terrain,x,y,width,height,mask,0,width);
        else MaskedPlaneAccess.applyLayer(dimension,layer,target,x,y,width,height,mask,0,width);
    }
}