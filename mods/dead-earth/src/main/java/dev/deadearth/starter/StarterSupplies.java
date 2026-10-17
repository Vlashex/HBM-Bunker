package dev.deadearth.starter;

import static dev.deadearth.config.DeadEarthConfig.*;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.deadearth.DeadEarth;
import dev.deadearth.campaign.SavedDataAccess;
import dev.deadearth.content.ZoneLoaderContent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.*;
import net.neoforged.neoforge.registries.*;

/** Only a native starter chest opened by a player can claim the world's shared stock. */
public final class StarterSupplies extends LootModifier {
    public static final ResourceLocation TABLE=ResourceLocation.parse("outpostzero:chests/hub_starter");
    public static final MapCodec<StarterSupplies> CODEC=RecordCodecBuilder.mapCodec(i->codecStart(i).apply(i,StarterSupplies::new));
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS=DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,DeadEarth.MOD_ID);
    static {SERIALIZERS.register("starter_supplies",()->CODEC);}
    public StarterSupplies(LootItemCondition[] conditions){super(conditions);}
    public static void register(IEventBus bus){SERIALIZERS.register(bus);}
    @Override public MapCodec<? extends IGlobalLootModifier> codec(){return CODEC;}
    private static void stacks(ObjectArrayList<ItemStack> stock,Item item,int count) {
        while(count>0){var stack=new ItemStack(item,Math.min(count,item.getDefaultMaxStackSize()));stock.add(stack);count-=stack.getCount();}
    }
    private static ObjectArrayList<ItemStack> supplies() {
        var stock=new ObjectArrayList<ItemStack>();
        stacks(stock,ZoneLoaderContent.ITEM.get(),i(STARTER_LOADERS));stacks(stock,Items.DIRT,i(STARTER_DIRT));
        for(var tree:List.of(Items.OAK_SAPLING,Items.SPRUCE_SAPLING,Items.BIRCH_SAPLING,Items.JUNGLE_SAPLING,Items.ACACIA_SAPLING,Items.DARK_OAK_SAPLING,Items.CHERRY_SAPLING,Items.MANGROVE_PROPAGULE))
            stacks(stock,tree,i(STARTER_SAPLINGS));
        return stock;
    }
    @Override protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generated,LootContext context) {
        if(!b(STARTER_ENABLED)||!TABLE.equals(context.getQueriedLootTableId()))return generated;
        var level=context.getLevel();var origin=context.getParamOrNull(LootContextParams.ORIGIN);
        if(level!=level.getServer().overworld()||origin==null||!Double.isFinite(origin.x)||!Double.isFinite(origin.y)||!Double.isFinite(origin.z)
                ||!(context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player)||player.isSpectator())return generated;
        if(!level.getServer().isSameThread())throw new IllegalStateException("Starter supplies outside server thread");
        var pos=BlockPos.containing(origin);if(!level.hasChunkAt(pos))return generated;
        var block=level.getBlockEntity(pos);
        // Native unpackLootTable clears its table BEFORE generating items. No recursion or forced chunk loads.
        if(!(block instanceof Container chest)||!(block instanceof RandomizableContainer random)||random.getLootTable()!=null)return generated;
        var stock=supplies();if(stock.isEmpty())return generated;
        int free=0;for(int slot=0;slot<chest.getContainerSize();slot++)if(chest.getItem(slot).isEmpty())free++;
        if(generated.stream().filter(s->!s.isEmpty()).count()+stock.size()>free) {
            DeadEarth.LOGGER.warn("Starter chest has insufficient room; shared stock remains unclaimed");return generated;
        }
        try {
            var receipt=SavedDataAccess.open(level,StarterStock.NAME,new SavedData.Factory<>(StarterStock::create,StarterStock::load));
            if(!receipt.enabled()||!receipt.claim(pos,player.getUUID(),Integer.toUnsignedLong(level.getServer().getTickCount())))return generated;
            // Acknowledged barrier before returning items: reconnects/restarts never issue another team stock.
            SavedDataAccess.checkpoint(level,StarterStock.NAME,receipt);
            generated.addAll(stock);
        } catch(RuntimeException invalid) {
            DeadEarth.LOGGER.error("Starter stock unavailable; no replacement receipt or personal kit was created",invalid);
        }
        return generated;
    }
}
