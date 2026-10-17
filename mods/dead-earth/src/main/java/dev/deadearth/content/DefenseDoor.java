package dev.deadearth.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.deadearth.defense.DefenseMaterial;
import dev.deadearth.defense.DefenseTier;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Two vanilla door halves; DefensePolicy stores their shared HP at the lower half. */
public final class DefenseDoor extends DoorBlock implements DefenseMaterial {
    public static final MapCodec<DefenseDoor> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
        Codec.STRING.xmap(DefenseTier::valueOf,DefenseTier::name).fieldOf("tier").forGetter(DefenseDoor::defenseTier),
        propertiesCodec()).apply(instance,DefenseDoor::new));
    private final DefenseTier tier;
    public DefenseDoor(DefenseTier tier,BlockBehaviour.Properties properties){super(DefenseContent.DOOR_TYPE,properties);this.tier=tier;}
    @Override public DefenseTier defenseTier(){return tier;}
    @Override public float getExplosionResistance(){return dev.deadearth.defense.DefenseConfig.blastResistance(tier);}
    @Override public MapCodec<DefenseDoor> codec(){return CODEC;}
}
