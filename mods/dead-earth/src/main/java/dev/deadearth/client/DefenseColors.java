package dev.deadearth.client;

import dev.deadearth.DeadEarth;
import dev.deadearth.content.DefenseContent;
import dev.deadearth.defense.DefenseMaterial;
import dev.deadearth.defense.DefenseTier;
import net.minecraft.world.item.BlockItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Darkening is model tint only; the original source PNG files remain byte-for-byte intact. */
@EventBusSubscriber(modid=DeadEarth.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class DefenseColors {
    private DefenseColors(){}
    private static int tint(DefenseTier tier){return switch(tier){case V1->0xFFFFFF;case V2->0xB0B0B0;case V3->0x737373;};}
    @SubscribeEvent public static void blocks(RegisterColorHandlersEvent.Block event){
        for(var holder:DefenseContent.ALL)event.register((state,level,pos,index)->index==0?tint(((DefenseMaterial)state.getBlock()).defenseTier()):0xFFFFFF,holder.get());
    }
    @SubscribeEvent public static void items(RegisterColorHandlersEvent.Item event){
        for(var holder:DefenseContent.ALL)event.register((stack,index)->index==0?tint(((DefenseMaterial)((BlockItem)stack.getItem()).getBlock()).defenseTier()):0xFFFFFF,holder.get());
    }
}
