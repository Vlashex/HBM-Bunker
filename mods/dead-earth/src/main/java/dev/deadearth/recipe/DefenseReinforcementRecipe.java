package dev.deadearth.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

/** Pure crafting-table/automation recipe: never mutates input stacks or uses a player/thread-local. */
public final class DefenseReinforcementRecipe implements CraftingRecipe {
    private static final Set<ResourceLocation> TOOLS=Set.of(ResourceLocation.parse("securitycraft:universal_block_reinforcer_lvl1"),
        ResourceLocation.parse("securitycraft:universal_block_reinforcer_lvl2"),ResourceLocation.parse("securitycraft:universal_block_reinforcer_lvl3"));
    private final Ingredient base;
    private final ItemStack result;
    public DefenseReinforcementRecipe(Ingredient base,ItemStack result){this.base=base;this.result=result.copy();}
    private static boolean tool(ItemStack stack){return !stack.isEmpty()&&stack.getCount()==1&&TOOLS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));}
    @Override public boolean matches(CraftingInput input,Level level){
        int bases=0,tools=0;
        for(var stack:input.items())if(!stack.isEmpty()){
            if(tool(stack))tools++;else if(base.test(stack))bases++;else return false;
        }return bases==1&&tools==1;
    }
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registries){return result.copy();}
    @Override public ItemStack getResultItem(HolderLookup.Provider registries){return result.copy();}
    @Override public boolean canCraftInDimensions(int width,int height){return width*height>=2;}
    @Override public CraftingBookCategory category(){return CraftingBookCategory.BUILDING;}
    @Override public RecipeSerializer<?> getSerializer(){return DefenseRecipes.REINFORCEMENT.get();}
    @Override public NonNullList<Ingredient> getIngredients(){
        var tools=TOOLS.stream().filter(BuiltInRegistries.ITEM::containsKey).map(BuiltInRegistries.ITEM::get).map(ItemStack::new);
        return NonNullList.of(Ingredient.EMPTY,base,Ingredient.of(tools));
    }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input){
        var remaining=NonNullList.withSize(input.size(),ItemStack.EMPTY);
        for(int slot=0;slot<input.size();slot++){
            var stack=input.getItem(slot);
            if(tool(stack)){
                var returned=stack.copyWithCount(1);
                // The native DAMAGE/MAX_DAMAGE/UNBREAKABLE components determine wear, including indestructible lvl3.
                // Remainders must be deterministic even when an automated crafter has no player or ServerLevel.
                if(returned.isDamageableItem()){
                    int damage=returned.getDamageValue()+1;
                    if(damage>=returned.getMaxDamage())continue;
                    returned.setDamageValue(damage);
                }remaining.set(slot,returned);
            }else if(stack.hasCraftingRemainingItem())remaining.set(slot,stack.getCraftingRemainingItem());
        }return remaining;
    }
    public static final class Serializer implements RecipeSerializer<DefenseReinforcementRecipe> {
        private static final MapCodec<DefenseReinforcementRecipe> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
            Ingredient.CODEC_NONEMPTY.fieldOf("base").forGetter(recipe->recipe.base),
            ItemStack.STRICT_CODEC.fieldOf("result").forGetter(recipe->recipe.result)).apply(instance,DefenseReinforcementRecipe::new));
        private static final StreamCodec<RegistryFriendlyByteBuf,DefenseReinforcementRecipe> STREAM_CODEC=StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC,recipe->recipe.base,ItemStack.STREAM_CODEC,recipe->recipe.result,DefenseReinforcementRecipe::new);
        @Override public MapCodec<DefenseReinforcementRecipe> codec(){return CODEC;}
        @Override public StreamCodec<RegistryFriendlyByteBuf,DefenseReinforcementRecipe> streamCodec(){return STREAM_CODEC;}
    }
}
