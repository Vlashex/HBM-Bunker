package dev.deadearth.gametest;

import dev.deadearth.zone.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_review") @PrefixGameTestTemplate(false)
public final class ZoneNativeGameTests {
    @SuppressWarnings("unchecked") @GameTest(template="empty",batch="zones_native",timeoutTicks=80)
    public static void unpoweredMeDriveCountsPhysicalCellWithoutFlushingAndSeesBufferedChanges(GameTestHelper h) throws Exception {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(3,2,3));
        var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:drive"));level.setBlock(pos,block.defaultBlockState(),3);var drive=level.getBlockEntity(pos);
        h.assertTrue(drive!=null&&ZoneCompatibility.me(drive),"Native ME Drive missing");
        var keyType=Class.forName("appeng.api.stacks.AEKey");var itemKey=Class.forName("appeng.api.stacks.AEItemKey");
        var key=itemKey.getMethod("of",ItemStack.class).invoke(null,new ItemStack(Items.DIAMOND));
        var generic=Class.forName("appeng.api.stacks.GenericStack").getConstructor(keyType,long.class).newInstance(key,4L);
        var component=(DataComponentType<List<Object>>)Class.forName("appeng.api.ids.AEComponents").getField("STORAGE_CELL_INV").get(null);
        var cell=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:item_storage_cell_1k")));cell.set(component,List.of(generic));
        Object inventory=drive.getClass().getMethod("getInternalInventory").invoke(drive);
        inventory.getClass().getMethod("setItemDirect",int.class,ItemStack.class).invoke(inventory,0,cell);
        h.runAfterDelay(5,()->{
            try {
                h.assertTrue(!(Boolean)drive.getClass().getMethod("isPowered").invoke(drive),"ME fixture unexpectedly powered");
                var field=inventory.getClass().getDeclaredField("inv");field.setAccessible(true);Object rawInventory=field.get(inventory);
                var raw=(ItemStack)rawInventory.getClass().getMethod("getStackInSlot",int.class).invoke(rawInventory,0);var before=raw.copy();
                var read=ZoneCompatibility.cell(drive,0);
                h.assertTrue(!read.unknown()&&read.stock().size()==1&&read.stock().getFirst().amount()==4,"Unpowered physical cell not counted");
                h.assertTrue(ItemStack.isSameItemSameComponents(before,raw),"Observation flushed or changed live cell components");
                Object original=drive.getClass().getMethod("getOriginalCellInventory",int.class).invoke(drive,0);
                h.assertTrue(original!=null,"Native original cache unavailable in fixture");
                Object iron=itemKey.getMethod("of",ItemStack.class).invoke(null,new ItemStack(Items.IRON_INGOT));
                var actionable=Class.forName("appeng.api.config.Actionable");var source=Class.forName("appeng.api.networking.security.IActionSource");
                original.getClass().getMethod("insert",keyType,long.class,actionable,source).invoke(original,iron,16L,actionable.getField("MODULATE").get(null),source.getMethod("empty").invoke(null));
                var afterInsert=raw.copy();var current=ZoneCompatibility.cell(drive,0);
                h.assertTrue(!current.unknown()&&current.stock().stream().anyMatch(s->s.item().is(Items.IRON_INGOT)&&s.amount()==16),"Buffered physical contents hidden by stale component snapshot");
                h.assertTrue(ItemStack.isSameItemSameComponents(afterInsert,raw),"Cached observation persisted live components");h.succeed();
            }catch(Exception error){throw new RuntimeException(error);}finally{level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);}
        });
    }
    @SuppressWarnings("unchecked") @GameTest(template="empty",batch="zones_native")
    public static void nativeHbmAmmoPowerDisabledAndHowardMagazineRemainDistinct(GameTestHelper h) throws Exception {
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(2,2,2));
        var state=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("hbm:turret_sentry")).defaultBlockState();
        var turret=(BlockEntity)Class.forName("com.hbm.tileentity.turret.BlockEntityTurretSentry").getConstructor(BlockPos.class,BlockState.class).newInstance(pos,state);turret.setLevel(level);
        turret.getClass().getField("isOn").setBoolean(turret,true);turret.getClass().getField("power").setLong(turret,1000000L);
        h.assertTrue(ZoneCompatibility.turret(turret).state()==ZoneScore.Defense.EMPTY,"Empty native Sentry classified as defense");
        var ammo=(List<ItemStack>)turret.getClass().getMethod("getAmmoTypesForDisplay").invoke(turret);
        h.assertTrue(!ammo.isEmpty(),"Native ammo fixture missing");var slots=(List<ItemStack>)turret.getClass().getField("inventory").get(turret);slots.set(1,ammo.getFirst().copyWithCount(8));
        var before=turret.saveWithoutMetadata(level.registryAccess()).copy();
        h.assertTrue(ZoneCompatibility.turret(turret).state()==ZoneScore.Defense.ARMED,"Native loaded powered Sentry not counted");
        h.assertTrue(before.equals(turret.saveWithoutMetadata(level.registryAccess())),"Turret observation consumed or changed ammo");
        turret.getClass().getField("isOn").setBoolean(turret,false);
        h.assertTrue(ZoneCompatibility.turret(turret).state()==ZoneScore.Defense.DISABLED,"Switched-off loaded turret counted as empty");
        var hs=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("hbm:turret_howard")).defaultBlockState();
        var howard=(BlockEntity)Class.forName("com.hbm.tileentity.turret.BlockEntityTurretHoward").getConstructor(BlockPos.class,BlockState.class).newInstance(pos,hs);howard.setLevel(level);
        howard.getClass().getField("isOn").setBoolean(howard,true);howard.getClass().getField("power").setLong(howard,1000000L);
        var loaded=howard.getClass().getDeclaredField("loaded");loaded.setAccessible(true);loaded.setInt(howard,100);
        h.assertTrue(ZoneCompatibility.turret(howard).state()==ZoneScore.Defense.ARMED,"Howard internal magazine ignored");h.succeed();
    }
    @GameTest(template="empty",batch="zones_emc")
    public static void actualEmcIsUnitValueAndLoaderCannotBeTransmuted(GameTestHelper h) {
        var client=FoundationGameTests.attach(h);
        try {
            var one=ZoneCompatibility.price(new ItemStack(Items.DIAMOND));var four=ZoneCompatibility.price(new ItemStack(Items.DIAMOND,4));
            h.assertTrue(!one.unknown()&&one.unit()==8192&&four.unit()==one.unit(),"EMC unit adapter wrong or unavailable");
            var loader=ZoneCompatibility.price(new ItemStack(dev.deadearth.content.ZoneLoaderContent.ITEM.get()));
            h.assertTrue(!loader.unknown()&&loader.unit()==0,"Loader obtained a transmutation bypass");h.succeed();
        } finally {client.close();}
    }
}
