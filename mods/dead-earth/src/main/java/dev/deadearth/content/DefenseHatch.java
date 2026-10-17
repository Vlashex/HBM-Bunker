package dev.deadearth.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.deadearth.defense.DefenseMaterial;
import dev.deadearth.defense.DefenseTier;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DefenseHatch extends TrapDoorBlock implements DefenseMaterial {
    public static final MapCodec<DefenseHatch> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
        Codec.STRING.xmap(DefenseTier::valueOf,DefenseTier::name).fieldOf("tier").forGetter(DefenseHatch::defenseTier),
        propertiesCodec()).apply(instance,DefenseHatch::new));
    private final DefenseTier tier;
    public DefenseHatch(DefenseTier tier,BlockBehaviour.Properties properties){super(DefenseContent.DOOR_TYPE,properties);this.tier=tier;}
    @Override public DefenseTier defenseTier(){return tier;}
    @Override public float getExplosionResistance(){return dev.deadearth.defense.DefenseConfig.blastResistance(tier);}
    @Override public MapCodec<DefenseHatch> codec(){return CODEC;}
}
