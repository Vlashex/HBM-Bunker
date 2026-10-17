package dev.deadearth.worldgen;

import com.mojang.serialization.MapCodec;
import dev.deadearth.DeadEarth;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.registries.*;

public final class WastelandWorldgen {
    private static final DeferredRegister<Feature<?>> FEATURES=DeferredRegister.create(Registries.FEATURE,DeadEarth.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> MODIFIERS=DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,DeadEarth.MOD_ID);
    public static final DeferredHolder<Feature<?>,WastelandFeature> TERRAIN=FEATURES.register("wasteland_terrain",WastelandFeature::new);
    public static final DeferredHolder<Feature<?>,DeadTrunksFeature> TRUNKS=FEATURES.register("dead_trunks",DeadTrunksFeature::new);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>,MapCodec<WastelandBiomeModifier>> CODEC=MODIFIERS.register("wasteland",()->WastelandBiomeModifier.CODEC);
    private WastelandWorldgen(){}
    public static void register(IEventBus bus){FEATURES.register(bus);MODIFIERS.register(bus);}
}
