package dev.deadearth.recipe;

import dev.deadearth.DeadEarth;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DefenseRecipes {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS=DeferredRegister.create(Registries.RECIPE_SERIALIZER,DeadEarth.MOD_ID);
    public static final DeferredHolder<RecipeSerializer<?>,DefenseReinforcementRecipe.Serializer> REINFORCEMENT=SERIALIZERS.register("reinforcement",DefenseReinforcementRecipe.Serializer::new);
    private DefenseRecipes(){}
    public static void register(IEventBus bus){SERIALIZERS.register(bus);}
}
