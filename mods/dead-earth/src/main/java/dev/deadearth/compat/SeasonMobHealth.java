package dev.deadearth.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

public final class SeasonMobHealth {
    private static final ResourceLocation ID=ResourceLocation.parse("dead_earth:season_health");
    private SeasonMobHealth() {}
    public static void apply(LivingEntity mob,double factor) {
        var attribute=mob.getAttribute(Attributes.MAX_HEALTH);
        if(attribute==null || !mob.isAlive()) return;
        var old=attribute.getModifier(ID);
        if ((old==null && factor==1) || (old!=null && Math.abs(old.amount()-(factor-1))<1e-9)) return;
        float before=mob.getMaxHealth(),health=mob.getHealth();
        attribute.removeModifier(ID);
        // Persist this owned modifier so health/max-health remain coherent across entity NBT load.
        if(factor!=1) attribute.addPermanentModifier(new AttributeModifier(ID,factor-1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        if(before>0) mob.setHealth(Math.min(mob.getMaxHealth(),health*mob.getMaxHealth()/before));
    }
}
