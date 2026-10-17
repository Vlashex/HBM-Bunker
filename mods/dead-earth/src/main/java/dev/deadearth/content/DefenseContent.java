package dev.deadearth.content;

import dev.deadearth.DeadEarth;
import dev.deadearth.defense.DefenseConfig;
import dev.deadearth.defense.DefenseTier;
import dev.deadearth.recipe.DefenseRecipes;
import java.util.List;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DefenseContent {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(DeadEarth.MOD_ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(DeadEarth.MOD_ID);
    private static final BlockSetType IRON=BlockSetType.IRON;
    public static final BlockSetType DOOR_TYPE=BlockSetType.register(new BlockSetType("dead_earth:defense",true,false,false,
        IRON.pressurePlateSensitivity(),IRON.soundType(),IRON.doorClose(),IRON.doorOpen(),IRON.trapdoorClose(),IRON.trapdoorOpen(),
        IRON.pressurePlateClickOff(),IRON.pressurePlateClickOn(),IRON.buttonClickOff(),IRON.buttonClickOn()));
    public static final DeferredBlock<DefenseWall> WALL_V1=wall("defense_wall_v1",DefenseTier.V1);
    public static final DeferredBlock<DefenseWall> WALL_V2=wall("defense_wall_v2",DefenseTier.V2);
    public static final DeferredBlock<DefenseWall> WALL_V3=wall("defense_wall_v3",DefenseTier.V3);
    public static final DeferredBlock<DefenseDoor> DOOR_V1=door("defense_door_v1",DefenseTier.V1);
    public static final DeferredBlock<DefenseDoor> DOOR_V2=door("defense_door_v2",DefenseTier.V2);
    public static final DeferredBlock<DefenseDoor> DOOR_V3=door("defense_door_v3",DefenseTier.V3);
    public static final DeferredBlock<DefenseHatch> HATCH_V1=hatch("defense_hatch_v1",DefenseTier.V1);
    public static final DeferredBlock<DefenseHatch> HATCH_V2=hatch("defense_hatch_v2",DefenseTier.V2);
    public static final DeferredBlock<DefenseHatch> HATCH_V3=hatch("defense_hatch_v3",DefenseTier.V3);
    public static final List<DeferredBlock<? extends Block>> ALL=List.of(WALL_V1,WALL_V2,WALL_V3,DOOR_V1,DOOR_V2,DOOR_V3,HATCH_V1,HATCH_V2,HATCH_V3);
    private DefenseContent(){}
    private static BlockBehaviour.Properties properties(Block copy,DefenseTier tier){
        return BlockBehaviour.Properties.ofFullCopy(copy).strength(DefenseConfig.hardness(tier),DefenseConfig.blastResistance(tier));
    }
    private static DeferredBlock<DefenseWall> wall(String name,DefenseTier tier){
        var block=BLOCKS.register(name,()->new DefenseWall(tier,properties(Blocks.IRON_BLOCK,tier)));ITEMS.registerSimpleBlockItem(block);return block;
    }
    private static DeferredBlock<DefenseDoor> door(String name,DefenseTier tier){
        var block=BLOCKS.register(name,()->new DefenseDoor(tier,properties(Blocks.IRON_DOOR,tier)));ITEMS.registerSimpleBlockItem(block);return block;
    }
    private static DeferredBlock<DefenseHatch> hatch(String name,DefenseTier tier){
        var block=BLOCKS.register(name,()->new DefenseHatch(tier,properties(Blocks.IRON_TRAPDOOR,tier)));ITEMS.registerSimpleBlockItem(block);return block;
    }
    public static void register(IEventBus bus){
        BLOCKS.register(bus);ITEMS.register(bus);DefenseRecipes.register(bus);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event)->{
            if(event.getTabKey()==CreativeModeTabs.BUILDING_BLOCKS)for(var block:ALL)event.accept(block.get());
        });
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event)->{
            for(var stack:java.util.stream.Stream.concat(event.getParentEntries().stream(),event.getSearchEntries().stream()).toList())
                if(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("outpostzero:steel_block"))event.remove(stack,CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        });
    }
}
