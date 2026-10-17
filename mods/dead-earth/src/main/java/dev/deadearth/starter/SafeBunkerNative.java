package dev.deadearth.starter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

/** Read-only adapter pinned to OutpostZero 1.0.20; never invokes its SavedData.computeIfAbsent or a placer. */
final class SafeBunkerNative {
    private static final String PLACER="dev.mostlyharmless.outpostzero.world.bunker.BunkerPlacer";
    private static final ResourceLocation LEGACY_STEEL=ResourceLocation.parse("outpostzero:steel_block"),DEFENSE_V1=ResourceLocation.parse("dead_earth:defense_wall_v1");
    private static final Set<String> NATIVE_FIELDS=Set.of("Placed","Finalised","SporeIntroCompleted","OriginX","OriginY","OriginZ","BarracksSpawn");
    private SafeBunkerNative() {}
    static boolean equivalentWall(ResourceLocation expected,ResourceLocation actual) {
        return expected.equals(LEGACY_STEEL)&&actual.equals(DEFENSE_V1)||expected.equals(DEFENSE_V1)&&actual.equals(LEGACY_STEEL);
    }
    private static Object call(Object receiver,String name,Object... args)throws ReflectiveOperationException {
        Class<?> type=receiver instanceof Class<?> c?c:receiver.getClass();
        for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            method.setAccessible(true);return method.invoke(Modifier.isStatic(method.getModifiers())?null:receiver,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static StructureTemplate template(ServerLevel level,String name)throws ReflectiveOperationException {
        return (StructureTemplate)call(Class.forName(PLACER),"loadTemplateDirect",level,ResourceLocation.parse("outpostzero:"+name));
    }
    static Recovery recover(ServerLevel level)throws Exception {
        var file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/outpostzero_starter_bunker.dat");
        if(!Files.exists(file))return null;
        var root=NbtIo.readCompressed(file,NbtAccounter.create(8*1024*1024));
        if(!root.contains("data",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Invalid native starter data root");
        return recover(level,root.getCompound("data"));
    }
    static Recovery recover(ServerLevel level,CompoundTag tag)throws Exception {
        if(!NATIVE_FIELDS.containsAll(tag.getAllKeys()))throw new IllegalArgumentException("Unknown native starter schema; preserved");
        if(!tag.contains("Placed",Tag.TAG_BYTE)||(tag.getByte("Placed")!=0&&tag.getByte("Placed")!=1)
                ||!tag.contains("Finalised",Tag.TAG_BYTE))throw new IllegalArgumentException("Invalid native starter flags");
        if(!tag.getBoolean("Placed"))return null;
        for(String field:List.of("OriginX","OriginY","OriginZ"))if(!tag.contains(field,Tag.TAG_INT))throw new IllegalArgumentException("Missing native origin");
        if(!tag.getBoolean("Finalised")||!tag.contains("BarracksSpawn",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Native starter is incomplete");
        var spawn=tag.getCompound("BarracksSpawn");
        for(String field:List.of("X","Y","Z"))if(!spawn.contains(field,Tag.TAG_INT))throw new IllegalArgumentException("Invalid native barracks");
        var spoke=Direction.byName(spawn.getString("Spoke"));if(spoke==null||!spoke.getAxis().isHorizontal())throw new IllegalArgumentException("Invalid native barracks spoke");
        var hub=new BlockPos(tag.getInt("OriginX"),tag.getInt("OriginY"),tag.getInt("OriginZ"));
        if(!level.isInWorldBounds(hub))throw new IllegalArgumentException("Native starter origin outside world");
        var recovery=new Recovery();var hubTemplate=template(level,"opz_hub");
        recovery.add(hubTemplate,hub,Rotation.NONE,true,false);
        Class<?> placer=Class.forName(PLACER);
        Object modules=call(Class.forName("dev.mostlyharmless.outpostzero.world.bunker.modules.OpzBunkerModules"),"current");
        Object plan=((Map<?,?>)call(modules,"plans")).get("starter");
        if(plan==null)throw new IllegalArgumentException("Native starter plan missing");
        var slots=new ArrayList<>((List<?>)call(plan,"slots"));
        slots.sort(Comparator.comparing(slot->{try{return !(boolean)call(slot,"spawn");}catch(Exception e){throw new IllegalStateException(e);}}));
        if(slots.isEmpty()||slots.size()>4)throw new IllegalArgumentException("Unrecognized native starter plan");
        var used=EnumSet.noneOf(Direction.class);var types=new HashSet<String>();int index=0;boolean verifiedSpawn=false;
        for(var slot:slots) {
            var chosen=(Optional<?>)call(placer,"resolveSlotModule",level,hub,modules,slot,types,"starter",++index);
            if(chosen.isEmpty())continue;Object module=chosen.get();
            long salt=(long)call(placer,"saltForSlot","starter",(String)call(module,"id"),index);
            var direction=(Direction)(used.isEmpty()?call(placer,"pickRoomSpoke",level,hub,salt):call(placer,"pickRoomSpokeExcluding",level,hub,used,salt));
            used.add(direction);types.add((String)call(module,"roomType"));
            var rotation=(Rotation)call(placer,"rotationForSpoke",direction);
            var attach=(BlockPos)call(placer,"hubSpokeAttachWorld",hub,direction);
            var anchor=(BlockPos)call(module,"connectAnchorLocal");
            var origin=attach.subtract(StructureTemplate.transform(anchor,Mirror.NONE,rotation,BlockPos.ZERO));
            var moduleTemplate=(StructureTemplate)call(placer,"loadTemplateDirect",level,(ResourceLocation)call(module,"template"));
            recovery.add(moduleTemplate,origin,rotation,true,false);
            if((boolean)call(slot,"spawn")&&(boolean)call(module,"canBeSpawn")) {
                var local=(Optional<?>)call(module,"spawnFloorLocal");var size=moduleTemplate.getSize();
                var floor=(BlockPos)local.orElse(null);if(floor==null)floor=new BlockPos(size.getX()/2,2,size.getZ()/2);
                var expected=origin.offset(StructureTemplate.transform(floor,Mirror.NONE,rotation,BlockPos.ZERO));
                if(direction!=spoke||!expected.equals(new BlockPos(spawn.getInt("X"),spawn.getInt("Y"),spawn.getInt("Z"))))
                    throw new IllegalArgumentException("Native plan changed since bunker placement; no guessed migration");
                verifiedSpawn=true;
            }
        }
        if(!verifiedSpawn)throw new IllegalArgumentException("Cannot verify native starter identity");
        var base=hub.offset(3,hubTemplate.getSize().getY(),5);var baseTemplate=template(level,"opz_hatch_base");
        recovery.add(baseTemplate,base,Rotation.NONE,true,false);
        var ring=template(level,"opz_hatch_ring");var top=template(level,"opz_hatch_top");
        int ringHeight=ring.getSize().getY();if(ringHeight<=0)throw new IllegalArgumentException("Invalid native hatch template");
        // Ring starts follow this grid, but native topY derives from surface height independently.
        int firstRingY=base.getY()+baseTemplate.getSize().getY();
        for(int y=firstRingY;y+top.getSize().getY()<=level.getMaxBuildHeight();y++) {
            recovery.add(top,new BlockPos(base.getX(),y,base.getZ()),Rotation.NONE,false,true);
            if((y-firstRingY)%ringHeight==0)recovery.add(ring,new BlockPos(base.getX(),y,base.getZ()),Rotation.NONE,false,false);
        }
        return recovery;
    }
    private record Candidate(SafeBunkerStore.Box box,List<Map.Entry<Long,Set<ResourceLocation>>> expected,boolean required,boolean top) {}
    static final class Recovery {
        private final List<Candidate> candidates=new ArrayList<>();
        private final List<SafeBunkerStore.Box> boxes=new ArrayList<>();
        private final Map<Long,ResourceLocation> originals=new LinkedHashMap<>(),matched=new LinkedHashMap<>();
        private int candidateIndex,blockIndex;private boolean topFound;
        List<SafeBunkerStore.Box> boxes(){return boxes;}
        Map<Long,ResourceLocation> originals(){return originals;}
        private void add(StructureTemplate template,BlockPos origin,Rotation rotation,boolean required,boolean top) {
            if(candidates.size()>1024)throw new IllegalArgumentException("Too many native recovery candidates");
            var settings=new StructurePlaceSettings().setRotation(rotation).setMirror(Mirror.NONE);
            var bb=template.getBoundingBox(settings,origin);
            var box=new SafeBunkerStore.Box(bb.minX(),bb.minY(),bb.minZ(),bb.maxX(),bb.maxY(),bb.maxZ());
            var tag=template.save(new CompoundTag());var palette=tag.getList("palette",Tag.TAG_COMPOUND);
            var expected=new LinkedHashMap<Long,Set<ResourceLocation>>();
            for(var raw:tag.getList("blocks",Tag.TAG_COMPOUND)) {
                var block=(CompoundTag)raw;var xyz=block.getList("pos",Tag.TAG_INT);var local=new BlockPos(xyz.getInt(0),xyz.getInt(1),xyz.getInt(2));
                var pos=origin.offset(StructureTemplate.transform(local,Mirror.NONE,rotation,BlockPos.ZERO));
                String id=palette.getCompound(block.getInt("state")).getString("Name");
                if(id.equals("minecraft:air")||id.equals("minecraft:structure_block")||id.equals("outpostzero:module_connect_marker")||id.equals("outpostzero:module_spawn_marker"))continue;
                Set<ResourceLocation> allowed;
                if(id.equals("minecraft:jigsaw")) {
                    var nbt=block.getCompound("nbt");
                    allowed=markerBlocks(nbt.getString(nbt.contains("name",Tag.TAG_STRING)?"name":"Name"));
                }
                else allowed=Set.of(ResourceLocation.parse(id));
                if(!allowed.isEmpty())expected.put(pos.asLong(),allowed);
            }
            if(expected.size()<16)throw new IllegalArgumentException("Native template has insufficient evidence");
            candidates.add(new Candidate(box,new ArrayList<>(expected.entrySet()),required,top));
        }
        boolean advance(ServerLevel level,int budget) {
            while(candidateIndex<candidates.size()&&budget>0) {
                var candidate=candidates.get(candidateIndex);
                while(blockIndex<candidate.expected.size()&&budget>0) {
                    var entry=candidate.expected.get(blockIndex);var pos=BlockPos.of(entry.getKey());
                    if(!level.hasChunkAt(pos))return false; // Wait for normal loading; never getChunk/force-load.
                    var actual=BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
                    // Recovery may observe a partly migrated old bunker while current templates use V1.
                    // Keep actual identities; campaign identity, original snapshot and deadline are unchanged.
                    if(entry.getValue().contains(actual)||entry.getValue().stream().anyMatch(expected->equivalentWall(expected,actual)))matched.put(entry.getKey(),actual);
                    blockIndex++;budget--;
                }
                if(blockIndex<candidate.expected.size())return false;
                boolean valid=matched.size()>=candidate.expected.size()*(candidate.required?0.70:0.90);
                if(candidate.top)valid=valid&&matched.values().stream().anyMatch(id->id.toString().equals("outpostzero:airlock_trapdoor")||id.toString().equals("minecraft:iron_trapdoor"));
                if(!valid&&candidate.required)throw new IllegalArgumentException("Native bunker template does not match loaded world");
                if(valid) {
                    if(candidate.top&&topFound)throw new IllegalArgumentException("Ambiguous native hatch top");
                    if(candidate.top)topFound=true;
                    boxes.add(candidate.box);originals.putAll(matched);
                    if(boxes.size()>SafeBunkerStore.MAX_BOXES||originals.size()>SafeBunkerStore.MAX_BLOCKS)throw new IllegalArgumentException("Native recovery exceeds bounds");
                }
                matched.clear();blockIndex=0;candidateIndex++;
            }
            if(candidateIndex==candidates.size()&&!topFound)throw new IllegalArgumentException("Cannot verify native hatch top");
            return candidateIndex==candidates.size();
        }
    }
    private static Set<ResourceLocation> markerBlocks(String name) {
        String id=switch(name) {
            case "opz:block/motion_sensor"->"outpostzero:motion_sensor";
            case "opz:block/door_bottom","opz:block/door_top"->"outpostzero:airlock_door";
            case "opz:block/patient_zero_skull"->"outpostzero:patient_zero_head";
            case "opz:block/minecraft_cauldron"->"minecraft:cauldron";
            default->"";
        };
        if(name.equals("opz:block/opzc_lantern"))return Set.of(ResourceLocation.parse("minecraft:lantern"),ResourceLocation.parse("outpostzerocampsites:lantern"));
        return id.isEmpty()?Set.of():Set.of(ResourceLocation.parse(id));
    }
}
