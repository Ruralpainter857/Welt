package org.pepsoft.minecraft;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** One grouped region-header read and one native decode, with worker-local 8 KiB scratch. */
public final class RegionHeaderAccess {
    private RegionHeaderAccess() { }
    private static final ThreadLocal<ByteBuffer> SCRATCH=ThreadLocal.withInitial(()->ByteBuffer.allocateDirect(8192));
    private static final AtomicLong CALLS=new AtomicLong();
    public static long completedCalls(){return CALLS.get();}
    static boolean read(RandomAccessFile file,int sectors,int[] offsets,int[] timestamps) throws IOException {
        return read(file,sectors,offsets,timestamps,NativeSlices::decodeRegionHeader);
    }
    static boolean read(RandomAccessFile file,int sectors,int[] offsets,int[] timestamps,java.util.function.Predicate<ByteBuffer> decode) throws IOException {
        if(sectors==0 || !Boolean.parseBoolean(System.getProperty("welt.native.regionHeader","true")))return false;
        ByteBuffer data=SCRATCH.get();data.clear().limit(sectors>1?8192:4096);
        try{
            while(data.hasRemaining())if(file.getChannel().read(data)<0)throw new EOFException();
        }catch(EOFException e){
            // Replay the established reader so corrupt offsets preceding EOF keep their original error order.
            file.seek(0);return false;
        }
        data.flip();
        if(Boolean.parseBoolean(System.getProperty("welt.native.regionHeaderKernel","true")) && decode.test(data)){data.order(ByteOrder.LITTLE_ENDIAN);CALLS.incrementAndGet();}
        else data.order(ByteOrder.BIG_ENDIAN);
        data.asIntBuffer().get(offsets);
        if(sectors>1){data.position(4096);data.asIntBuffer().get(timestamps);}
        return true;
    }
}