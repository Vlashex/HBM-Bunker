package dev.deadearth.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.deadearth.defense.DefenseMaterial;
import dev.deadearth.defense.DefenseTier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DefenseWall extends Block implements DefenseMaterial {
    public static final MapCodec<DefenseWall> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
        Codec.STRING.xmap(DefenseTier::valueOf,DefenseTier::name).fieldOf("tier").forGetter(DefenseWall::defenseTier),
        propertiesCodec()).apply(instance,DefenseWall::new));
    private final DefenseTier tier;
    public DefenseWall(DefenseTier tier,BlockBehaviour.Properties properties){super(properties);this.tier=tier;}
    @Override public DefenseTier defenseTier(){return tier;}
    @Override public float getExplosionResistance(){return dev.deadearth.defense.DefenseConfig.blastResistance(tier);}
    @Override public MapCodec<DefenseWall> codec(){return CODEC;}
}
