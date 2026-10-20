package dev.deadearth.legacyprobe.machinery;

import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.gameplay.HbmToolApi;
import dev.deadearth.legacy.machinery.LegacyMachinery;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.items.CapabilityItemHandler;

/**
 * Opt-in final-stage physical assertions. Caller supplies two empty machines in its disposable
 * world.
 */
public final class MachineryProbe {
  private MachineryProbe() {}

  private static void require(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  private static int number(Object o, String name) {
    return ((Number) Reflect.call(o, name)).intValue();
  }

  private static boolean empty(Object s) {
    return (Boolean) Reflect.call(s, "isEmpty/func_190926_b");
  }

  private static Object stack(String id, int count) {
    return Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), count, 0);
  }

  private static void tick(Object tile, int n) {
    for (int i = 0; i < n; i++) Reflect.call(tile, "update/func_73660_a");
  }

  private static void fill(Object energy, int amount) {
    for (int i = 0; number(energy, "getEnergyStored") < amount && i < 10000; i++)
      Reflect.call(energy, "receiveEnergy", amount, false);
    require(number(energy, "getEnergyStored") >= amount, "probe energy provision");
  }

  private static Object inventory(Object tile) {
    return Reflect.call(tile, "getCapability", CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
  }

  private static Object power(Object tile) {
    return Reflect.call(tile, "getCapability", CapabilityEnergy.ENERGY, null);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> verify(Object charger, Object processor) throws Exception {
    require(
        Boolean.getBoolean("deadearth.testControls")
            && Files.exists(Paths.get(".dead-earth-isolated-native-test")),
        "isolated machine test gate");
    require(
        charger.getClass().getName().endsWith("MachineTile$Charger")
            && processor.getClass().getName().endsWith("MachineTile$Processor"),
        "physical own tiles");
    Object cfg = Reflect.get(LegacyMachinery.class, "settings");
    int cycle = ((Number) Reflect.get(cfg, "processorCycleTicks")).intValue(),
        cost = ((Number) Reflect.get(cfg, "processorFEPerCycle")).intValue();
    Object inventory = inventory(charger), energy = power(charger);
    require(empty(Reflect.call(inventory, "getStackInSlot", 0)), "charger starts empty");
    Object tool = stack("dead_earth:mining_gadget_v1", 1),
        item = Reflect.call(tool, "getItem/func_77973_b");
    require(Reflect.is(item, "com.hbm.api.energymk2.IBatteryItem"), "native HBM tool battery");
    HbmToolApi.setCharge(item, tool, 0L);
    require(
        empty(Reflect.call(inventory, "insertItem", 0, tool, false)), "charger accepts HBM tool");
    fill(energy, 4000);
    int before = number(energy, "getEnergyStored");
    tick(charger, 1);
    Object charged = Reflect.call(inventory, "extractItem", 0, 1, false);
    require(HbmToolApi.charge(item, charged) > 0, "native HBM item receives charge");
    require(number(energy, "getEnergyStored") < before, "accepted native charge spends FE");
    inventory = inventory(processor);
    energy = power(processor);
    require(empty(Reflect.call(inventory, "getStackInSlot", 0)), "processor starts empty");
    require(
        empty(Reflect.call(inventory, "insertItem", 0, stack("minecraft:coal_ore", 1), false)),
        "ore dictionary ore accepted");
    require(
        !empty(Reflect.call(inventory, "insertItem", 0, stack("minecraft:cobblestone", 1), true)),
        "non ore rejected");
    fill(energy, cost);
    before = number(energy, "getEnergyStored");
    tick(processor, cycle);
    require(empty(Reflect.call(inventory, "getStackInSlot", 0)), "exactly one ore consumed");
    require(number(energy, "getEnergyStored") == before - cost, "exact cycle cost");
    Object coal = Reflect.call(inventory, "extractItem", 1, 64, false);
    require(
        !empty(coal)
            && Reflect.call(Reflect.call(coal, "getItem/func_77973_b"), "getRegistryName")
                .toString()
                .equals("minecraft:coal"),
        "native Fortune coal drop");
    List<Object> slots = (List<Object>) Reflect.get(processor, "slots");
    for (int i = 1; i < slots.size(); i++) slots.set(i, stack("minecraft:cobblestone", 64));
    require(
        empty(Reflect.call(inventory, "insertItem", 0, stack("minecraft:coal_ore", 1), false)),
        "blocked-cycle input");
    fill(energy, cost);
    before = number(energy, "getEnergyStored");
    tick(processor, cycle);
    require(Reflect.get(processor, "pending") != null, "full output retains single native roll");
    require(!empty(Reflect.call(inventory, "getStackInSlot", 0)), "full output retains ore");
    require(number(energy, "getEnergyStored") == before, "full output retains FE");
    Object tag = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    Reflect.call(processor, "writeToNBT/func_189515_b", tag);
    Object restored = Reflect.make("dev.deadearth.legacy.machinery.MachineTile$Processor");
    Reflect.call(restored, "readFromNBT/func_145839_a", tag);
    Object world = Reflect.call(processor, "getWorld/func_145831_w"),
        pos = Reflect.call(processor, "getPos/func_174877_v");
    Reflect.call(restored, "setWorld/func_145834_a", world);
    Reflect.call(restored, "setPos/func_174878_a", pos);
    Reflect.call(world, "setTileEntity/func_175690_a", pos, restored);
    processor = restored;
    inventory = inventory(processor);
    energy = power(processor);
    require(Reflect.get(processor, "pending") != null, "pending roll restores from NBT");
    tick(processor, cycle + 2);
    require(
        number(energy, "getEnergyStored") == before
            && !empty(Reflect.call(inventory, "getStackInSlot", 0)),
        "restored full batch stays transactional");
    Reflect.call(inventory, "extractItem", 1, 64, false);
    tick(processor, 1);
    require(
        empty(Reflect.call(inventory, "getStackInSlot", 0)),
        "opening output capacity commits once");
    require(number(energy, "getEnergyStored") == before - cost, "restored batch debits once");
    require(Reflect.get(processor, "pending") == null, "settled pending batch is cleared");
    tick(processor, cycle + 1);
    require(
        number(energy, "getEnergyStored") == before - cost,
        "empty processor cannot duplicate cycle");
    Map<String, Object> report = new LinkedHashMap<String, Object>();
    report.put("nativeHbmCharging", true);
    report.put("nativeOreDrops", true);
    report.put("blockedOutputTransactional", true);
    report.put("pendingNbtRoundTrip", true);
    return report;
  }
}
