package dev.deadearth.starter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Identity and absolute campaign deadline are persisted once; login and capture never restart them. */
public final class SafeBunkerStore extends SavedData {
    public static final String NAME="dead_earth_safe_bunker";
    public static final int MAX_BLOCKS=131072, MAX_BOXES=192, MAX_VOLUME=524288;
    private UUID campaign;
    private long deadline;
    private volatile boolean recorded;
    private String source="";
    private List<Box> boxes=List.of();
    private Map<Long,ResourceLocation> originals=Map.of();
    private CompoundTag unsupported;
    private SafeBunkerStore() {}

    public record Box(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Box {
            if(minX>maxX||minY>maxY||minZ>maxZ||minX < -30000000||maxX>30000000||minZ < -30000000||maxZ>30000000
                    ||minY < -2048||maxY>4095||(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1)>MAX_VOLUME)
                throw new IllegalArgumentException("Invalid safe bunker bounds");
        }
        public boolean contains(double x,double y,double z){return x>=minX&&x<maxX+1.0&&y>=minY&&y<maxY+1.0&&z>=minZ&&z<maxZ+1.0;}
        public AABB aabb(){return new AABB(minX,minY,minZ,maxX+1.0,maxY+1.0,maxZ+1.0);}
        public long volume(){return (long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);}
    }
    public static SafeBunkerStore create(UUID campaign,long deadline) {
        if(campaign==null||deadline<0)throw new IllegalArgumentException("Invalid safe bunker campaign/deadline");
        var store=new SafeBunkerStore();store.campaign=campaign;store.deadline=deadline;store.setDirty();return store;
    }
    public boolean enabled(){return unsupported==null;}
    public boolean recorded(){return enabled()&&recorded;}
    public long deadline(){return deadline;}
    public List<Box> boxes(){return boxes;}
    public Map<Long,ResourceLocation> originals(){return originals;}
    public boolean active(UUID campaign,long now){return enabled()&&recorded&&this.campaign.equals(campaign)&&now>=0&&now<deadline;}
    public void record(List<Box> boxes,Map<Long,ResourceLocation> originals,String source) {
        if(!enabled()||recorded)throw new IllegalStateException("Safe bunker is already recorded or unsupported");
        if(boxes.isEmpty()||boxes.size()>MAX_BOXES||originals.size()>MAX_BLOCKS
                ||boxes.stream().mapToLong(Box::volume).sum()>MAX_VOLUME
                ||!(source.equals("native_capture")||source.equals("native_migration")))throw new IllegalArgumentException("Invalid safe bunker snapshot");
        var immutableBoxes=List.copyOf(boxes);
        for(var e:originals.entrySet()) {
            var pos=BlockPos.of(e.getKey());
            if(e.getValue()==null||immutableBoxes.stream().noneMatch(b->b.contains(pos.getX(),pos.getY(),pos.getZ())))
                throw new IllegalArgumentException("Original block outside safe bunker");
        }
        this.boxes=immutableBoxes;this.originals=Map.copyOf(originals);this.source=source;recorded=true;setDirty();
    }
    public boolean contains(double x,double y,double z){return boxes.stream().anyMatch(b->b.contains(x,y,z));}
    public boolean intersects(AABB bounds){return boxes.stream().anyMatch(b->b.aabb().intersects(bounds));}
    public boolean blocksReplacement(BlockPos pos,ResourceLocation current,ResourceLocation next,boolean all) {
        if(!recorded()||current.equals(next)||current.equals(ResourceLocation.withDefaultNamespace("air")))return false;
        return all?contains(pos.getX(),pos.getY(),pos.getZ()):current.equals(originals.get(pos.asLong()));
    }
    /** Minkowski expansion checks the complete segment, including a teleport across an entire room. */
    public boolean blocksTravel(AABB body,Vec3 delta) {
        if(intersects(body))return false; // Existing intruders can leave; the bounded sweep removes the rest without loot.
        var center=body.getCenter();var destination=center.add(delta);
        for(var box:boxes) {
            var expanded=box.aabb().inflate(body.getXsize()/2,body.getYsize()/2,body.getZsize()/2);
            if(expanded.contains(destination)||expanded.clip(center,destination).isPresent())return true;
        }
        return false;
    }
    public static SafeBunkerStore load(CompoundTag tag,HolderLookup.Provider registries) {
        var store=new SafeBunkerStore();
        try {
            if(!tag.contains("schemaVersion",Tag.TAG_INT)||tag.getInt("schemaVersion")!=1||!tag.hasUUID("campaignId")
                    ||!tag.contains("deadline",Tag.TAG_LONG)||tag.getLong("deadline")<0||!tag.contains("recorded",Tag.TAG_BYTE)
                    ||(tag.getByte("recorded")!=0&&tag.getByte("recorded")!=1))throw new IllegalArgumentException("Invalid safe bunker receipt");
            store.campaign=tag.getUUID("campaignId");store.deadline=tag.getLong("deadline");
            if(tag.getBoolean("recorded")) {
                if(!tag.contains("boxes",Tag.TAG_LIST)||!tag.contains("originals",Tag.TAG_LIST)||!tag.contains("source",Tag.TAG_STRING))
                    throw new IllegalArgumentException("Incomplete safe bunker snapshot");
                var boxList=(ListTag)tag.get("boxes");var blockList=(ListTag)tag.get("originals");
                if(boxList.getElementType()!=Tag.TAG_INT_ARRAY||!blockList.isEmpty()&&blockList.getElementType()!=Tag.TAG_COMPOUND)
                    throw new IllegalArgumentException("Invalid safe bunker list types");
                var rawBoxes=tag.getList("boxes",Tag.TAG_INT_ARRAY);var rawBlocks=tag.getList("originals",Tag.TAG_COMPOUND);
                if(rawBoxes.size()>MAX_BOXES||rawBlocks.size()>MAX_BLOCKS)throw new IllegalArgumentException("Oversized safe bunker snapshot");
                var boxes=new ArrayList<Box>();
                for(var raw:rawBoxes){int[] b=((IntArrayTag)raw).getAsIntArray();if(b.length!=6)throw new IllegalArgumentException("Invalid bounds");boxes.add(new Box(b[0],b[1],b[2],b[3],b[4],b[5]));}
                var blocks=new LinkedHashMap<Long,ResourceLocation>();
                for(var raw:rawBlocks) {
                    var block=(CompoundTag)raw;
                    if(!block.contains("p",Tag.TAG_LONG)||!block.contains("id",Tag.TAG_STRING)
                            ||blocks.put(block.getLong("p"),ResourceLocation.parse(block.getString("id")))!=null)
                        throw new IllegalArgumentException("Invalid/duplicate original block");
                }
                store.record(boxes,blocks,tag.getString("source"));store.setDirty(false);
            }
        } catch(RuntimeException invalid){store.unsupported=tag.copy();store.boxes=List.of();store.originals=Map.of();store.setDirty(false);}
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(!enabled())return unsupported.copy();
        tag.putInt("schemaVersion",1);tag.putUUID("campaignId",campaign);tag.putLong("deadline",deadline);tag.putBoolean("recorded",recorded);
        if(recorded) {
            tag.putString("source",source);var savedBoxes=new ListTag();var savedBlocks=new ListTag();
            for(var b:boxes)savedBoxes.add(new IntArrayTag(new int[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ}));
            originals.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getKey)).forEach(e->{
                var block=new CompoundTag();block.putLong("p",e.getKey());block.putString("id",e.getValue().toString());savedBlocks.add(block);
            });
            tag.put("boxes",savedBoxes);tag.put("originals",savedBlocks);
        }
        return tag;
    }
}
