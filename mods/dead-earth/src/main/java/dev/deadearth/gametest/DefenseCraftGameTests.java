package dev.deadearth.gametest;

import dev.deadearth.defense.DefenseMaterial;
import dev.deadearth.defense.DefenseTier;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native recipe/remainder contract shared by the crafting table and automation. */
@GameTestHolder("dead_earth_defense")
@PrefixGameTestTemplate(false)
public final class DefenseCraftGameTests {
    private static ResourceLocation id(String name){return ResourceLocation.parse(name);}
    private static Item item(GameTestHelper h,String name){
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(id(name)),"Missing defense ingredient/item: "+name);
        return BuiltInRegistries.ITEM.get(id(name));
    }
    private static CraftingRecipe recipe(GameTestHelper h,String name){
        var value=h.getLevel().getRecipeManager().byKey(id("dead_earth:"+name)).orElseThrow(()->new IllegalStateException("Missing defense recipe: "+name)).value();
        h.assertTrue(value instanceof CraftingRecipe,"Defense recipe is not native crafting: "+name);return (CraftingRecipe)value;
    }
    private static CraftingInput grid(ItemStack... stacks){
        var slots=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));for(int i=0;i<stacks.length;i++)slots.set(i,stacks[i]);return CraftingInput.of(3,3,slots);
    }
    private static ItemStack base(CraftingRecipe recipe){return recipe.getIngredients().getFirst().getItems()[0].copy();}

    @GameTest(template="empty",batch="defense_crafting")
    public static void reinforcerRemainderKeepsComponentsAndChargesExactlyOneNativeUse(GameTestHelper h){
        var recipe=recipe(h,"defense_wall_v1");
        for(int tier=1;tier<=3;tier++){
            var tool=new ItemStack(item(h,"securitycraft:universal_block_reinforcer_lvl"+tier));
            tool.set(DataComponents.CUSTOM_NAME,Component.literal("Owned reinforcement tool "+tier));
            if(tool.isDamageableItem())tool.setDamageValue(7);
            var original=tool.copy();var input=grid(base(recipe),tool);
            h.assertTrue(recipe.matches(input,h.getLevel()),"Reinforcement rejected native tier "+tier);
            var result=recipe.assemble(input,h.getLevel().registryAccess());
            h.assertTrue(result.is(item(h,"dead_earth:defense_wall_v1"))&&result.getCount()==1,"V1 output must be exactly one wall");
            var remaining=recipe.getRemainingItems(input);var returned=remaining.get(1);
            h.assertTrue(returned.is(tool.getItem())&&returned.getCount()==1,"Native tool was lost/duplicated, tier "+tier);
            var expected=original.copy();if(expected.isDamageableItem())expected.setDamageValue(expected.getDamageValue()+1);
            h.assertTrue(ItemStack.isSameItemSameComponents(returned,expected),"Remainder changed native components or wrong durability, tier "+tier);
            h.assertTrue(ItemStack.matches(tool,original),"Remainder calculation mutated the input tool");
            h.assertTrue(ItemStack.matches(recipe.getRemainingItems(input).get(1),returned),"Repeated automation remainder probe changed the result");
            h.assertTrue(remaining.stream().mapToInt(ItemStack::getCount).sum()==1,"Reinforcement returned an extra ingredient/tool");
            if(tier==3)h.assertTrue(!original.isDamageableItem(),"Pinned lvl3 reinforcer lost native indestructibility");
            if(original.isDamageableItem()){
                var last=original.copy();last.setDamageValue(last.getMaxDamage()-1);
                var lastInput=grid(base(recipe),last);h.assertTrue(recipe.matches(lastInput,h.getLevel()),"Last native tool use must still craft");
                h.assertTrue(recipe.getRemainingItems(lastInput).get(1).isEmpty(),"Exhausted reinforcer was returned instead of breaking");
                h.assertTrue(last.getDamageValue()==last.getMaxDamage()-1,"Break calculation mutated the real input");
            }
        }h.succeed();
    }

    @GameTest(template="empty",batch="defense_crafting")
    public static void reinforcementRejectsExtraToolsAndIngredientsAndPreservesUnbreakable(GameTestHelper h){
        var recipe=recipe(h,"defense_wall_v1");var tool=new ItemStack(item(h,"securitycraft:universal_block_reinforcer_lvl1"));
        h.assertTrue(!recipe.matches(grid(base(recipe)),h.getLevel()),"Wall can be crafted without its tool");
        h.assertTrue(!recipe.matches(grid(base(recipe),tool,tool.copy()),h.getLevel()),"Recipe accepts two tools");
        h.assertTrue(!recipe.matches(grid(base(recipe),tool,new ItemStack(Items.DIRT)),h.getLevel()),"Recipe accepts unrelated ingredients");
        h.assertTrue(!recipe.matches(grid(new ItemStack(Items.DIRT),tool),h.getLevel()),"Recipe accepts a wrong base block");
        tool.set(DataComponents.UNBREAKABLE,new net.minecraft.world.item.component.Unbreakable(true));
        var input=grid(base(recipe),tool);var result=recipe.getRemainingItems(input).get(1);
        h.assertTrue(ItemStack.matches(tool,result),"Native unbreakable tool components were changed");h.succeed();
    }

    @GameTest(template="empty",batch="defense_crafting")
    public static void nineMaterialsAndSteelGatedRecipesAreRegisteredWithoutNuclearBlastResistance(GameTestHelper h){
        for(var tier:DefenseTier.values())for(String form:List.of("wall","door","hatch")){
            String name="defense_"+form+"_"+tier.name().toLowerCase(Locale.ROOT);var key=id("dead_earth:"+name);
            h.assertTrue(BuiltInRegistries.BLOCK.containsKey(key),"Missing defense block: "+key);var block=BuiltInRegistries.BLOCK.get(key);
            h.assertTrue(block instanceof DefenseMaterial material&&material.defenseTier()==tier,"Wrong native material tier: "+key);
            h.assertTrue(Float.isFinite(block.getExplosionResistance())&&block.getExplosionResistance()<100,"Ordinary defense acquired nuclear/indestructible blast resistance: "+key);
            var output=recipe(h,name).getResultItem(h.getLevel().registryAccess());
            h.assertTrue(output.is(item(h,key.toString()))&&output.getCount()==1,"Wrong defense recipe output: "+key);
        }
        var v1=new ItemStack(item(h,"dead_earth:defense_wall_v1"));var steel=new ItemStack(item(h,"hbm:plate_steel"));
        h.assertTrue(recipe(h,"defense_wall_v2").matches(grid(v1,steel,steel.copy()),h.getLevel()),"V2 does not require its prior tier plus two HBM steel plates");
        h.assertTrue(!recipe(h,"defense_wall_v2").matches(grid(v1,new ItemStack(Items.IRON_INGOT),new ItemStack(Items.IRON_INGOT)),h.getLevel()),"V2 bypasses HBM steel");
        var titanium=new ItemStack(item(h,"hbm:plate_titanium"));var dura=new ItemStack(item(h,"hbm:plate_dura_steel"));
        h.assertTrue(recipe(h,"defense_wall_v3").matches(grid(new ItemStack(item(h,"dead_earth:defense_wall_v2")),titanium,titanium.copy(),dura,dura.copy()),h.getLevel()),"V3 lost prior tier/titanium/advanced steel cost");h.succeed();
    }

    @GameTest(template="empty",batch="defense_crafting")
    public static void nativeItemPlacementCreatesWorkingDoorHalvesAndHandRedstoneHatches(GameTestHelper h){
        var level=h.getLevel();
        try(var client=FoundationGameTests.attach(h)){
            var player=client.player();player.setGameMode(GameType.SURVIVAL);
            for(int tier=1;tier<=3;tier++)for(String form:List.of("door","hatch")){
                var floor=h.absolutePos(new BlockPos(2+(tier-1)*3,1,form.equals("door")?2:6));var pos=floor.above();
                level.setBlock(floor,Blocks.IRON_BLOCK.defaultBlockState(),3);player.setPos(Vec3.atCenterOf(pos.south(2)));
                var block=item(h,"dead_earth:defense_"+form+"_v"+tier);
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(block));
                var hit=new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false);
                var placed=player.getMainHandItem().useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
                h.assertTrue(placed.consumesAction()&&level.getBlockState(pos).is(((BlockItem)block).getBlock()),"Native item placement failed: "+form+" V"+tier);
                h.assertTrue(player.getMainHandItem().isEmpty(),"Placement did not consume exactly one item");
                if(form.equals("door"))h.assertTrue(level.getBlockState(pos.above()).is(((BlockItem)block).getBlock()),"Native door did not place its upper half");
                var interaction=new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false);
                level.getBlockState(pos).useWithoutItem(level,player,interaction);
                h.assertTrue(level.getBlockState(pos).getValue(BlockStateProperties.OPEN),"Defense cannot open by hand: "+form+" V"+tier);
                if(form.equals("door"))h.assertTrue(level.getBlockState(pos.above()).getValue(BlockStateProperties.OPEN),"Manual opening did not synchronize the other door half");
                level.getBlockState(pos).useWithoutItem(level,player,interaction);
                h.assertTrue(!level.getBlockState(pos).getValue(BlockStateProperties.OPEN),"Defense cannot close by hand");
                var power=pos.north();level.setBlock(power,Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
                h.assertTrue(level.getBlockState(pos).getValue(BlockStateProperties.OPEN),"Defense cannot open from native redstone");
                level.setBlock(power,Blocks.AIR.defaultBlockState(),3);
                h.assertTrue(!level.getBlockState(pos).getValue(BlockStateProperties.OPEN),"Defense cannot close when redstone is removed");
            }
        }h.succeed();
    }

    @GameTest(template="empty",batch="defense_crafting")
    public static void templateLoadingMigratesOnlyRetiredOutpostSteelAndKeepsUnrelatedSecurityCraft(GameTestHelper h)throws Exception{
        var tag=net.minecraft.nbt.TagParser.parseTag("{size:[3,1,1],palette:[{Name:\"outpostzero:steel_block\"},{Name:\"securitycraft:reinforced_stone\"},{Name:\"minecraft:iron_block\"}],blocks:[{pos:[0,0,0],state:0},{pos:[1,0,0],state:1},{pos:[2,0,0],state:2}],entities:[]}");
        var template=new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
        template.load(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK),tag);
        var palette=template.save(new net.minecraft.nbt.CompoundTag()).getList("palette",net.minecraft.nbt.Tag.TAG_COMPOUND);
        var names=new HashSet<String>();for(var entry:palette)names.add(((net.minecraft.nbt.CompoundTag)entry).getString("Name"));
        h.assertTrue(names.contains("dead_earth:defense_wall_v1")&&!names.contains("outpostzero:steel_block"),"Native template kept the retired OutpostZero wall");
        h.assertTrue(names.contains("securitycraft:reinforced_stone")&&names.contains("minecraft:iron_block"),"Template replacement changed unrelated SecurityCraft/vanilla blocks");
        h.assertTrue(h.getLevel().getRecipeManager().byKey(id("outpostzero:steel_block")).isEmpty(),"Retired OutpostZero steel block remains craftable");h.succeed();
    }

    @GameTest(template="empty",batch="defense_crafting")
    public static void nativeAe2CraftingPatternsExposeReusableLvl3AndChangingDamageKeysForLvl1And2(GameTestHelper h)throws Exception{
        var api=Class.forName("appeng.api.crafting.PatternDetailsHelper");
        var patternApi=Class.forName("appeng.api.crafting.IPatternDetails");
        var inputApi=Class.forName("appeng.api.crafting.IPatternDetails$IInput");
        var keyType=Class.forName("appeng.api.stacks.AEKey");var itemKey=Class.forName("appeng.api.stacks.AEItemKey");
        var genericStack=Class.forName("appeng.api.stacks.GenericStack");
        var holder=h.getLevel().getRecipeManager().byKey(id("dead_earth:defense_wall_v1")).orElseThrow();var recipe=(CraftingRecipe)holder.value();
        for(int tier=1;tier<=3;tier++){
            var tool=new ItemStack(item(h,"securitycraft:universal_block_reinforcer_lvl"+tier));
            tool.set(DataComponents.CUSTOM_NAME,Component.literal("AE2 native remainder tier "+tier));
            var input=grid(base(recipe),tool);var output=recipe.assemble(input,h.getLevel().registryAccess());
            h.assertTrue(recipe.matches(input,h.getLevel()),"AE2 fixture does not match the native reinforcement recipe");
            // CraftingInput.of trims empty rows/columns. AE2 encoded patterns require all nine sparse slots.
            var sparse=new ItemStack[9];Arrays.fill(sparse,ItemStack.EMPTY);
            for(int slot=0;slot<input.size();slot++)sparse[slot%input.width()+3*(slot/input.width())]=input.getItem(slot);
            var encoded=(ItemStack)api.getMethod("encodeCraftingPattern",RecipeHolder.class,ItemStack[].class,ItemStack.class,boolean.class,boolean.class)
                .invoke(null,holder,sparse,output,false,false);
            var pattern=api.getMethod("decodePattern",ItemStack.class,net.minecraft.world.level.Level.class).invoke(null,encoded,h.getLevel());
            h.assertTrue(pattern!=null&&pattern.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern"),
                "Native AE2 rejected tier "+tier+" pattern="+(pattern==null?"null":pattern.getClass().getName())+" sparseSlots="+sparse.length+" recipeGrid="+input.width()+"x"+input.height());
            var toolKey=itemKey.getMethod("of",ItemStack.class).invoke(null,tool);Object toolInput=null;
            for(var ingredient:(Object[])patternApi.getMethod("getInputs").invoke(pattern)){
                for(var possible:(Object[])inputApi.getMethod("getPossibleInputs").invoke(ingredient)){
                    var key=genericStack.getMethod("what").invoke(possible);
                    if(toolKey.equals(key))toolInput=ingredient;
                }
            }
            h.assertTrue(toolInput!=null,"AE2 native pattern omitted the reinforcement tool");
            var remainingKey=inputApi.getMethod("getRemainingKey",keyType).invoke(toolInput,toolKey);
            h.assertTrue(remainingKey!=null,"AE2 native pattern dropped the tool remainder");
            if(tier==3){
                for(int cycle=0;cycle<8;cycle++){
                    h.assertTrue(toolKey.equals(remainingKey),"Native lvl3 remainder changed AEItemKey on cycle "+cycle);
                    h.assertTrue((Boolean)inputApi.getMethod("isValid",keyType,net.minecraft.world.level.Level.class).invoke(toolInput,remainingKey,h.getLevel()),"AE2 cannot reuse native lvl3 remainder");
                    remainingKey=inputApi.getMethod("getRemainingKey",keyType).invoke(toolInput,remainingKey);
                }
            }else{
                h.assertTrue(!toolKey.equals(remainingKey),"Durability-changing lvl"+tier+" unexpectedly kept the same AEItemKey");
                h.assertTrue(!(Boolean)inputApi.getMethod("isValid",keyType,net.minecraft.world.level.Level.class).invoke(toolInput,remainingKey,h.getLevel()),"Exact AE2 pattern silently accepted a different damage component");
            }
            @SuppressWarnings("unchecked") var remainders=(List<ItemStack>)pattern.getClass().getMethod("getRemainingItems",CraftingInput.class).invoke(pattern,input);
            var expected=tool.copy();if(expected.isDamageableItem())expected.setDamageValue(expected.getDamageValue()+1);
            h.assertTrue(remainders.size()==input.size()&&ItemStack.matches(remainders.get(1),expected),"Native AE2 assembler remainder lost components or durability");
        }h.succeed();
    }
}
