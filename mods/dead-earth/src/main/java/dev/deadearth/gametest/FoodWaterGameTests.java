package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses the pinned mods through reflection, without adding either mod to compile dependencies. */
@GameTestHolder("dead_earth_food")
@PrefixGameTestTemplate(false)
public final class FoodWaterGameTests {
    private static ResourceLocation id(String value){return ResourceLocation.parse(value);}
    private static Item item(GameTestHelper helper,String name) {
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id(name)),"Missing food/water item: "+name);
        return BuiltInRegistries.ITEM.get(id(name));
    }
    private static Class<?> thirstClass(String name)throws ClassNotFoundException{return Class.forName("dev.ghen.thirst."+name);}
    private static Object thirstApi(String method,ItemStack stack)throws Exception {
        return thirstClass("api.ThirstHelper").getMethod(method,ItemStack.class).invoke(null,stack);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Object thirst(ServerPlayer player)throws Exception {
        var attachment=(Supplier)thirstClass("foundation.common.capability.ModAttachment").getField("PLAYER_THIRST").get(null);
        return player.getData(attachment);
    }
    private static int hydration(Object thirst)throws Exception{return ((Number)thirst.getClass().getMethod("getThirst").invoke(thirst)).intValue();}
    private static Object prepareConsumer(ServerPlayer player)throws Exception {
        player.setGameMode(GameType.SURVIVAL);player.setHealth(player.getMaxHealth());player.removeAllEffects();
        player.getInventory().clearContent();player.getFoodData().setFoodLevel(4);player.getFoodData().setSaturation(0);
        var thirst=thirst(player);thirst.getClass().getMethod("setThirst",int.class).invoke(thirst,4);
        thirst.getClass().getMethod("setQuenched",int.class).invoke(thirst,0);
        thirst.getClass().getMethod("setExhaustion",float.class).invoke(thirst,0f);return thirst;
    }
    /** Reproduces LivingEntity.completeUsingItem's native finish + NeoForge Finish event sequence. */
    private static ItemStack consume(ServerPlayer player,ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);var consumed=stack.copy();
        var result=stack.finishUsingItem(player.level(),player);
        var finish=new LivingEntityUseItemEvent.Finish(player,consumed,0,result);NeoForge.EVENT_BUS.post(finish);
        player.setItemInHand(InteractionHand.MAIN_HAND,finish.getResultStack());return finish.getResultStack();
    }
    private static int purity(ItemStack stack)throws Exception {
        return ((Number)thirstClass("content.purity.WaterPurity").getMethod("getPurity",ItemStack.class).invoke(null,stack)).intValue();
    }
    private static ItemStack smelt(GameTestHelper helper,ItemStack input,String recipeId,int expectedPurity)throws Exception {
        var recipeInput=new SingleRecipeInput(input);var level=helper.getLevel();
        var holder=level.getRecipeManager().getRecipeFor(RecipeType.SMELTING,recipeInput,level)
            .orElseThrow(()->new IllegalStateException("No native smelting recipe for purity "+purityUnchecked(input)));
        helper.assertTrue(holder.id().equals(id(recipeId)),"Wrong water purification recipe: "+holder.id());
        var result=holder.value().assemble(recipeInput,level.registryAccess());
        helper.assertTrue(result.is(Items.POTION)&&result.get(DataComponents.POTION_CONTENTS)!=null
            &&result.get(DataComponents.POTION_CONTENTS).is(Potions.WATER),"Purification lost the water potion component");
        helper.assertTrue(purity(result)==expectedPurity,"Native smelting produced purity "+purity(result)+", expected "+expectedPurity);
        return result;
    }
    private static String purityUnchecked(ItemStack stack) {
        try{return Integer.toString(purity(stack));}catch(Exception error){return "unknown";}
    }
    @GameTest(template="empty",batch="food_water")
    public static void nativeSmeltingPurifiesWaterAndDrinkingConsumesOnlyOneBottle(GameTestHelper helper)throws Exception {
        var dirty=PotionContents.createItemStack(Items.POTION,Potions.WATER);
        dirty=(ItemStack)thirstClass("content.purity.WaterPurity").getMethod("addPurity",ItemStack.class,int.class).invoke(null,dirty,0);
        helper.assertTrue(purity(dirty)==0,"Dirty water fixture has no explicit purity zero");
        var acceptable=smelt(helper,dirty,"thirst:water_bottle_from_smelting_acceptable",2);
        var purified=smelt(helper,acceptable,"thirst:water_bottle_from_smelting_purified",3);
        helper.assertTrue(purity(dirty)==0&&purity(acceptable)==2,"Recipe assembly mutated its input water");
        try(var client=FoundationGameTests.attach(helper)) {
            var player=client.player();var thirst=prepareConsumer(player);purified.setCount(2);
            helper.assertTrue(player.isAlive()&&!player.isCreative()&&!player.isSpectator(),"Water consumer must be a living survival player");
            int maximum=purified.getMaxStackSize();helper.assertTrue(maximum>=2,"Native water bottles cannot form a stack of two");
            int gain=((Number)thirstApi("getThirst",purified)).intValue();helper.assertTrue(gain>0,"Native clean water has no hydration value");
            var remaining=consume(player,purified);
            helper.assertTrue(hydration(thirst)==Math.min(20,4+gain),"Real water drinking did not apply hydration exactly once");
            helper.assertTrue(remaining.is(Items.POTION)&&remaining.getCount()==1&&purity(remaining)==3,"Drinking a stack of two did not leave one purified bottle");
            helper.assertTrue(player.getInventory().countItem(Items.GLASS_BOTTLE)==1,"Drinking did not return exactly one empty bottle");
            int beforeLast=hydration(thirst);var empty=consume(player,remaining);
            helper.assertTrue(hydration(thirst)==Math.min(20,beforeLast+gain),"Last purified bottle was consumed without restoring hydration exactly once");
            helper.assertTrue(empty.is(Items.GLASS_BOTTLE)&&empty.getCount()==1,"Last water bottle did not become one empty glass bottle");
            int emptyBottles=player.getInventory().countItem(Items.GLASS_BOTTLE);
            helper.assertTrue(emptyBottles==2,"Expected one empty bottle in hand and one in another inventory slot; countItem includes the selected slot, actual="+emptyBottles);
            helper.assertTrue(maximum==16,"Configured water bottle stack size is not 16: "+maximum);
        }helper.succeed();
    }
    @GameTest(template="empty",batch="food_water")
    public static void farmersDelightSoupsRestoreBothHungerAndThirstThroughNativeConsumption(GameTestHelper helper)throws Exception {
        try(var client=FoundationGameTests.attach(helper)) {
            var player=client.player();
            for(String name:List.of("farmersdelight:vegetable_soup","farmersdelight:onion_soup")) {
                var thirst=prepareConsumer(player);var soup=new ItemStack(item(helper,name),2);
                helper.assertTrue((Boolean)thirstApi("isFood",soup),"Soup is absent from Thirst foods: "+name);
                int gain=((Number)thirstApi("getThirst",soup)).intValue();helper.assertTrue(gain>0,"Soup has no hydration: "+name);
                helper.assertTrue(soup.getFoodProperties(player)!=null,"Farmer's Delight soup has no native food properties: "+name);
                var remaining=consume(player,soup);
                helper.assertTrue(player.getFoodData().getFoodLevel()>4,"Native soup consumption did not restore hunger: "+name);
                helper.assertTrue(hydration(thirst)==Math.min(20,4+gain),"Native soup consumption did not restore thirst exactly once: "+name);
                helper.assertTrue(remaining.is(item(helper,name))&&remaining.getCount()==1,"Eating soup did not consume exactly one serving: "+name);
            }
        }helper.succeed();
    }
    private static ItemStack craft(GameTestHelper helper,String name,String output,int count,ItemStack... ingredients) {
        var holder=helper.getLevel().getRecipeManager().byKey(id(name)).orElseThrow(()->new IllegalStateException("Missing early food recipe: "+name));
        helper.assertTrue(holder.value() instanceof CraftingRecipe,"Kitchen/seed recipe is not ordinary crafting: "+name);
        var grid=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));for(int i=0;i<ingredients.length;i++)grid.set(i,ingredients[i]);
        var input=CraftingInput.of(3,3,grid);var recipe=(CraftingRecipe)holder.value();
        helper.assertTrue(recipe.matches(input,helper.getLevel()),"Native crafting does not match early ingredients: "+name);
        var result=recipe.assemble(input,helper.getLevel().registryAccess());
        helper.assertTrue(result.is(item(helper,output))&&result.getCount()==count,"Wrong early food crafting output: "+name);
        if(Arrays.stream(ingredients).anyMatch(s->s.is(Items.WATER_BUCKET)))
            helper.assertTrue(recipe.getRemainingItems(input).stream().filter(s->s.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum()==1,"Water bucket was not returned by crafting: "+name);
        return result;
    }
    private static ItemStack stack(Item item){return new ItemStack(item);}
    @GameTest(template="empty",batch="food_recipes")
    public static void earlyCropRecipesAndKitchenProgressionExistInTheRealRecipeManager(GameTestHelper helper) {
        DeadEarth.LOGGER.info("DEAD_EARTH_FOOD_ITEMS {}",BuiltInRegistries.ITEM.keySet().stream()
            .filter(key->Set.of("farmersdelight","thirst").contains(key.getNamespace())).map(ResourceLocation::toString).sorted().collect(Collectors.joining(",")));
        craft(helper,"hbm_pack_balance:food/cabbage_seeds","farmersdelight:cabbage_seeds",2,stack(Items.WHEAT_SEEDS),stack(Items.BONE_MEAL));
        craft(helper,"hbm_pack_balance:food/tomato_seeds","farmersdelight:tomato_seeds",2,stack(Items.WHEAT_SEEDS),stack(Items.WHEAT_SEEDS),stack(Items.BONE_MEAL));
        craft(helper,"hbm_pack_balance:food/onion","farmersdelight:onion",1,stack(Items.WHEAT_SEEDS),stack(Items.BONE_MEAL),stack(Items.BONE_MEAL));
        craft(helper,"hbm_pack_balance:food/rice","farmersdelight:rice",1,stack(Items.WHEAT_SEEDS),stack(Items.BONE_MEAL),stack(Items.WATER_BUCKET));
        craft(helper,"farmersdelight:cutting_board","farmersdelight:cutting_board",1,stack(Items.STICK),stack(Items.OAK_PLANKS),stack(Items.OAK_PLANKS),stack(Items.STICK),stack(Items.OAK_PLANKS),stack(Items.OAK_PLANKS));
        craft(helper,"farmersdelight:cooking_pot","farmersdelight:cooking_pot",1,stack(Items.BRICK),stack(Items.WOODEN_SHOVEL),stack(Items.BRICK),stack(Items.IRON_INGOT),stack(Items.WATER_BUCKET),stack(Items.IRON_INGOT),stack(Items.IRON_INGOT),stack(Items.IRON_INGOT),stack(Items.IRON_INGOT));
        for(String soup:List.of("vegetable_soup","onion_soup")) {
            String name="farmersdelight:cooking/"+soup;
            var recipe=helper.getLevel().getRecipeManager().byKey(id(name)).orElseThrow(()->new IllegalStateException("Missing cooking-pot meal: "+name)).value();
            helper.assertTrue(id("farmersdelight:cooking").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())),"Soup has no native cooking-pot recipe: "+name);
            helper.assertTrue(recipe.getResultItem(helper.getLevel().registryAccess()).is(item(helper,"farmersdelight:"+soup)),"Cooking-pot recipe lost its meal: "+name);
        }helper.succeed();
    }
    @GameTest(template="empty",batch="food_worldgen")
    public static void overworldBiomesDoNotGenerateFarmersDelightWildCropsOrMushroomColonies(GameTestHelper helper) {
        var registry=helper.getLevel().registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        for(String patch:List.of("wild_beetroots","wild_cabbages","wild_carrots","wild_onions","wild_potatoes","wild_rice","wild_tomatoes","brown_mushroom_colony","red_mushroom_colony"))
            helper.assertTrue(registry.containsKey(id("farmersdelight:patch_"+patch)),"Pinned Farmer's Delight feature missing; worldgen assertion would be vacuous: "+patch);
        var biomes=helper.getLevel().getServer().overworld().getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        helper.assertTrue(!biomes.isEmpty(),"Overworld generator has no biomes");
        for(var biome:biomes)for(var stage:biome.value().getGenerationSettings().features())for(var feature:stage) {
            var key=feature.unwrapKey().orElseThrow().location();
            helper.assertTrue(!key.getNamespace().equals("farmersdelight")||!(key.getPath().startsWith("patch_wild_")||key.getPath().contains("mushroom_colony")),
                "Wild crop/mushroom feature survived in an Overworld biome: "+key+" / "+biome.unwrapKey().orElseThrow().location());
        }helper.succeed();
    }
}
