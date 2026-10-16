package dev.deadearth.content;

import dev.deadearth.DeadEarth;
import dev.deadearth.zone.ZoneLoaders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class ZoneLoaderContent {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(DeadEarth.MOD_ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(DeadEarth.MOD_ID);
    public static final DeferredBlock<Block> LOADER=BLOCKS.register("zone_loader",()->new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.LODESTONE).strength(4,20)) {
        @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){super.onPlace(state,level,pos,old,moving);if(!old.is(this)&&level instanceof ServerLevel server)ZoneLoaders.activate(server,pos);}
        @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving){if(!next.is(this)&&level instanceof ServerLevel server)ZoneLoaders.deactivate(server,pos);super.onRemove(state,level,pos,next,moving);}
        @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
            if(level instanceof ServerLevel server){boolean active=ZoneLoaders.activate(server,pos);player.displayClientMessage(Component.translatable(active?"message.dead_earth.loader_active":"message.dead_earth.loader_limit"),false);}
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    });
    public static final DeferredItem<BlockItem> ITEM=ITEMS.registerSimpleBlockItem(LOADER);
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);bus.addListener(ZoneLoaders::register);bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e)->{if(e.getTabKey()==CreativeModeTabs.FUNCTIONAL_BLOCKS)e.accept(ITEM);});}
}
