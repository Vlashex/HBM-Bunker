package dev.deadearth.compat;

import dev.deadearth.starter.SafeBunker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Proxy;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Pinned HBM beta.10 worker snapshot adapter; never reads live blocks or loads chunks. */
public final class HbmBunkerProtection {
    private HbmBunkerProtection() {}
    public static Object mask(ServerLevel level,int cx,int cz,int sub,LevelChunkSection source,Object nativeMask) {
        if(source==null||!SafeBunker.intersectsSection(level,cx,cz,sub))return nativeMask;
        try {
            var type=Class.forName("com.hbm.interfaces.BitMask");
            var next=type.getMethod("nextSetBit",int.class);
            var allowed=new BitSet(4096);boolean rejected=false;
            var pos=new BlockPos.MutableBlockPos();int yBase=level.getMinBuildHeight()+(sub<<4);
            for(int index=(int)next.invoke(nativeMask,0);index>=0&&index<4096;index=(int)next.invoke(nativeMask,index+1)) {
                int x=index&15,y=(index>>>8)&15,z=(index>>>4)&15;
                pos.set((cx<<4)|x,yBase|y,(cz<<4)|z);
                if(SafeBunker.blocksReplacement(level,pos,source.getBlockState(x,y,z),Blocks.AIR.defaultBlockState()))rejected=true;
                else allowed.set(index);
            }
            if(!rejected)return nativeMask;
            // A private filtered mask leaves the caller's native/off-heap allocation unchanged.
            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->switch(method.getName()) {
                case "get"->allowed.get((int)args[0]);
                case "nextSetBit"->allowed.nextSetBit((int)args[0]);
                case "nextClearBit"->allowed.nextClearBit((int)args[0]);
                case "previousSetBit"->allowed.previousSetBit((int)args[0]);
                case "previousClearBit"->allowed.previousClearBit((int)args[0]);
                case "isEmpty"->allowed.isEmpty();case "cardinality"->(long)allowed.cardinality();
                case "length"->allowed.length();case "size","logicalSize"->4096;
                case "toLongArray"->allowed.toLongArray();case "free"->null;
                case "toString"->"DeadEarthFilteredBlastMask";
                case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];
                default->throw new UnsupportedOperationException("Unexpected HBM read-only mask mutation: "+method.getName());
            });
        }catch(ReflectiveOperationException invalid){throw new IllegalStateException("Unsupported pinned HBM blast mask",invalid);}
    }
    public static Long2ObjectOpenHashMap<BlockState> states(Object snapshot,Long2ObjectOpenHashMap<BlockState> changes) {
        if(changes.isEmpty())return changes;
        try {
            var type=snapshot.getClass();var chunk=(LevelChunk)type.getMethod("chunk").invoke(snapshot);
            if(!(chunk.getLevel() instanceof ServerLevel level))return changes;
            var sections=(LevelChunkSection[])type.getField("sections").get(snapshot);
            boolean intersects=false;
            for(int n=0;n<sections.length&&!intersects;n++)intersects=SafeBunker.intersectsSection(level,chunk.getPos().x,chunk.getPos().z,n);
            if(!intersects)return changes;
            Long2ObjectOpenHashMap<BlockState> filtered=null;
            for(var entry:changes.long2ObjectEntrySet()) {
                var pos=BlockPos.of(entry.getLongKey());int sub=level.getSectionIndex(pos.getY());
                if(sub<0||sub>=sections.length||sections[sub]==null)continue;
                var old=sections[sub].getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
                if(SafeBunker.blocksReplacement(level,pos,old,entry.getValue())) {
                    if(filtered==null)filtered=new Long2ObjectOpenHashMap<>(changes);
                    filtered.remove(entry.getLongKey());
                }
            }
            return filtered==null?changes:filtered;
        }catch(ReflectiveOperationException invalid){throw new IllegalStateException("Unsupported pinned HBM section snapshot",invalid);}
    }
}
