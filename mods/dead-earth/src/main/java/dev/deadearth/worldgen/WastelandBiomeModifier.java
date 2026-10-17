package dev.deadearth.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.*;

public record WastelandBiomeModifier(HolderSet<Biome> biomes,Holder<PlacedFeature> terrain,Holder<PlacedFeature> trunks) implements BiomeModifier {
    public static final MapCodec<WastelandBiomeModifier> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        Biome.LIST_CODEC.fieldOf("biomes").forGetter(WastelandBiomeModifier::biomes),
        PlacedFeature.CODEC.fieldOf("terrain").forGetter(WastelandBiomeModifier::terrain),
        PlacedFeature.CODEC.fieldOf("trunks").forGetter(WastelandBiomeModifier::trunks)).apply(i,WastelandBiomeModifier::new));
    @Override public MapCodec<? extends BiomeModifier> codec(){return CODEC;}
    @Override public void modify(Holder<Biome> biome,Phase phase,ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if(phase!=Phase.AFTER_EVERYTHING||!biomes.contains(biome)||biome.unwrapKey().map(k->!k.location().getNamespace().equals("minecraft")).orElse(true))return;
        var generation=builder.getGenerationSettings();
        for(var stage:GenerationStep.Decoration.values())generation.getFeatures(stage).removeIf(h->h.unwrapKey()
            .map(k->WastelandPolicy.removesFeature(k.location().toString(),stage==GenerationStep.Decoration.VEGETAL_DECORATION)).orElse(false));
        for(var category:MobCategory.values())builder.getMobSpawnSettings().getSpawner(category).removeIf(spawn->
            WastelandPolicy.removesSpawn(BuiltInRegistries.ENTITY_TYPE.getKey(spawn.type).toString(),category==MobCategory.MONSTER));
        var settings=WastelandConfig.settings();builder.getSpecialEffects().waterColor(settings.waterColor()).waterFogColor(settings.waterFogColor());
        generation.getFeatures(GenerationStep.Decoration.LOCAL_MODIFICATIONS).add(terrain);
        generation.getFeatures(GenerationStep.Decoration.VEGETAL_DECORATION).add(trunks);
    }
}
