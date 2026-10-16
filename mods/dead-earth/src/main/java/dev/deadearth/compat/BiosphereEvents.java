package dev.deadearth.compat;

import dev.deadearth.climate.SeasonState;
import java.util.ArrayDeque;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid="dead_earth")
public final class BiosphereEvents {
    private static final TagKey<EntityType<?>> MUTANTS=TagKey.create(Registries.ENTITY_TYPE,ResourceLocation.parse("mutationcraft:mutants"));
    private record Extra(ServerLevel level,EntityType<?> type,double x,double y,double z) {}
    private static final ArrayDeque<Extra> EXTRAS=new ArrayDeque<>();
    private static boolean addingExtra;
    private BiosphereEvents() {}
    public static boolean mutant(Entity entity) { return entity!=null && entity.getType().is(MUTANTS); }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void damage(LivingIncomingDamageEvent event) {
        if(!event.isCanceled() && !event.getEntity().level().isClientSide() && mutant(event.getSource().getEntity()))
            event.setAmount((float)(event.getAmount()*SeasonState.parasites()));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void spawn(FinalizeSpawnEvent event) {
        if(addingExtra || event.isSpawnCancelled() || !mutant(event.getEntity())
                || event.getSpawnType()!=MobSpawnType.NATURAL || !(event.getEntity().level() instanceof ServerLevel level)) return;
        double factor=SeasonState.parasites();
        if(factor<1) {if(level.random.nextDouble()>=factor) event.setSpawnCancelled(true);}
        else if(factor>1 && EXTRAS.size()<64 && level.random.nextDouble()<factor-1)
            EXTRAS.addLast(new Extra(level,event.getEntity().getType(),event.getX(),event.getY(),event.getZ()));
    }
    @SubscribeEvent public static void tick(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level)) return;
        if(level.getGameTime()%20==0) for(var entity:level.getAllEntities())
            if(entity instanceof LivingEntity living && mutant(living)) SeasonMobHealth.apply(living,SeasonState.parasites());
        if(EXTRAS.isEmpty() || level.players().isEmpty()) return;
        int budget=2,attempts=EXTRAS.size();
        var reserved=new java.util.EnumMap<MobCategory,Integer>(MobCategory.class);
        while(budget>0 && attempts-->0 && !EXTRAS.isEmpty()) {
            var extra=EXTRAS.removeFirst();if(extra.level()!=level) {EXTRAS.addLast(extra);continue;}
            if(SeasonState.parasites()<=1 || !level.hasChunkAt(net.minecraft.core.BlockPos.containing(extra.x(),extra.y(),extra.z()))) continue;
            var state=level.getChunkSource().getLastSpawnState();
            if(state==null) continue;
            MobCategory category=extra.type().getCategory();
            int cap=category.getMaxInstancesPerChunk()*state.getSpawnableChunkCount()/289;
            if(state.getMobCategoryCounts().getInt(category)+reserved.getOrDefault(category,0)>=cap) continue;
            var created=extra.type().create(level);
            if(!(created instanceof Mob mob)) continue;
            mob.moveTo(extra.x()+level.random.nextInt(5)-2,extra.y(),extra.z()+level.random.nextInt(5)-2,level.random.nextFloat()*360,0);
            if(tryNaturalExtra(level,mob)) {budget--;reserved.merge(category,1,Integer::sum);}

        }
    }
    /** The same placement and event checks as native natural spawning, with no forced chunks. */
    public static boolean tryNaturalExtra(ServerLevel level,Mob mob) {
        var pos=mob.blockPosition();
        if(!level.hasChunksAt(pos.offset(-24,-1,-24),pos.offset(24,2,24))
                || !level.getWorldBorder().isWithinBounds(pos)
                || level.getNearestPlayer(mob,24)!=null) return false;
        if(!net.minecraft.world.entity.SpawnPlacements.getPlacementType(mob.getType()).isSpawnPositionOk(level,pos,mob.getType())
                || !net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(mob.getType(),level,MobSpawnType.NATURAL,pos,level.random)
                || !net.neoforged.neoforge.event.EventHooks.checkSpawnPosition(mob,level,MobSpawnType.NATURAL)
                || !level.noCollision(mob)
                || level.getEntitiesOfClass(Mob.class,mob.getBoundingBox().inflate(24),BiosphereEvents::mutant).size()>=32) return false;
        addingExtra=true;
        try {
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob,level,
                    level.getCurrentDifficultyAt(pos),MobSpawnType.NATURAL,null);
            if(mob.isSpawnCancelled()) return false;
            if(!level.addFreshEntity(mob)) return false;
            SeasonMobHealth.apply(mob,SeasonState.parasites());return true;
        } finally {addingExtra=false;}
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {EXTRAS.clear();addingExtra=false;}
}
