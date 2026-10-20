package dev.deadearth.legacy.machinery;

import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.gameplay.HbmToolApi;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.*;
import net.minecraftforge.items.CapabilityItemHandler;

/**
 * Compact FE machines. All mutation is on the logical server owner thread, without chunk tickets.
 */
public abstract class MachineTile extends TileEntity implements ITickable {
  private final boolean processor;
  private final List<Object> slots = new ArrayList<Object>();
  private Object handler;
  private int energy, progress;
  private List<Object> pending;
  private Object pendingInput;
  private NBTTagCompound preserved;
  private boolean fault;
  private final IEnergyStorage power =
      new IEnergyStorage() {
        public int receiveEnergy(int maximum, boolean simulate) {
          if (maximum <= 0 || fault || !owner()) return 0;
          int n =
              Math.min(
                  maximum, Math.min(cfg().maxReceiveFE, Math.max(0, cfg().capacityFE - energy)));
          if (!simulate && n > 0) {
            energy += n;
            dirty();
          }
          return n;
        }

        public int extractEnergy(int max, boolean simulate) {
          return 0;
        }

        public int getEnergyStored() {
          return energy;
        }

        public int getMaxEnergyStored() {
          return cfg().capacityFE;
        }

        public boolean canExtract() {
          return false;
        }

        public boolean canReceive() {
          return !fault;
        }
      };

  protected MachineTile(boolean processor) {
    this.processor = processor;
    for (int i = 0; i < (processor ? 10 : 1); i++) slots.add(MachineStacks.empty());
  }

  public static final class Charger extends MachineTile {
    public Charger() {
      super(false);
    }
  }

  public static final class Processor extends MachineTile {
    public Processor() {
      super(true);
    }
  }

  private static MachinerySettings cfg() {
    return LegacyMachinery.settings();
  }

  private Object world() {
    return Reflect.call(this, "getWorld/func_145831_w");
  }

  private Object pos() {
    return Reflect.call(this, "getPos/func_174877_v");
  }

  private boolean owner() {
    Object w = world();
    if (w == null || (Boolean) Reflect.get(w, "isRemote/field_72995_K")) return false;
    Object s = Reflect.call(w, "getMinecraftServer/func_73046_m");
    return s != null && (Boolean) Reflect.call(s, "isCallingFromMinecraftThread/func_152345_ab");
  }

  private void dirty() {
    Reflect.call(this, "markDirty/func_70296_d");
  }

  public boolean hasCapability(Capability<?> cap, EnumFacing face) {
    return cap == CapabilityEnergy.ENERGY
        || cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
        || super.hasCapability(cap, face);
  }

  @SuppressWarnings("unchecked")
  public <T> T getCapability(Capability<T> cap, EnumFacing face) {
    if (cap == CapabilityEnergy.ENERGY) return (T) power;
    if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return (T) handler();
    return super.getCapability(cap, face);
  }

  private Object handler() {
    if (handler != null) return handler;
    Class<?> api = Reflect.type("net.minecraftforge.items.IItemHandler");
    handler =
        Proxy.newProxyInstance(
            api.getClassLoader(),
            new Class<?>[] {api},
            (o, m, a) -> {
              String n = m.getName();
              if (n.equals("getSlots")) return slots.size();
              if (n.equals("getSlotLimit")) return processor ? 64 : 1;
              if (n.equals("getStackInSlot")) {
                int slot = (Integer) a[0];
                check(slot);
                return MachineStacks.copy(slots.get(slot));
              }
              if (n.equals("isItemValid")) return valid((Integer) a[0], a[1]);
              if (n.equals("insertItem")) return insert((Integer) a[0], a[1], (Boolean) a[2]);
              if (n.equals("extractItem"))
                return extract((Integer) a[0], (Integer) a[1], (Boolean) a[2]);
              if (n.equals("toString")) return "DeadEarthMachineInventory";
              if (n.equals("hashCode")) return System.identityHashCode(o);
              if (n.equals("equals")) return o == a[0];
              return null;
            });
    return handler;
  }

  private void check(int slot) {
    if (slot < 0 || slot >= slots.size())
      throw new IllegalArgumentException("Machine slot out of range");
  }

  private boolean chargeable(Object stack) {
    if (MachineStacks.empty(stack)) return false;
    if (Reflect.is(MachineStacks.item(stack), "com.hbm.api.energymk2.IBatteryItem")) return true;
    return Reflect.call(stack, "getCapability", CapabilityEnergy.ENERGY, null) != null;
  }

  private boolean valid(int slot, Object stack) {
    check(slot);
    return !fault && slot == 0 && (processor ? MachineStacks.ore(stack) : chargeable(stack));
  }

  private Object insert(int slot, Object stack, boolean simulate) {
    check(slot);
    if (MachineStacks.empty(stack)) return MachineStacks.empty();
    if (!owner() || !valid(slot, stack)) return MachineStacks.copy(stack);
    Object old = slots.get(slot);
    if (!MachineStacks.empty(old) && !MachineStacks.same(old, stack))
      return MachineStacks.copy(stack);
    int room = Math.min(processor ? 64 : 1, MachineStacks.max(stack)) - MachineStacks.count(old),
        n = Math.min(Math.max(0, room), MachineStacks.count(stack));
    if (!simulate && n > 0) {
      slots.set(slot, MachineStacks.count(stack, MachineStacks.count(old) + n));
      dirty();
    }
    return MachineStacks.count(stack, MachineStacks.count(stack) - n);
  }

  private Object extract(int slot, int amount, boolean simulate) {
    check(slot);
    if (amount <= 0 || !owner() || fault || processor && slot == 0) return MachineStacks.empty();
    Object old = slots.get(slot);
    int n = Math.min(amount, MachineStacks.count(old));
    if (n <= 0) return MachineStacks.empty();
    Object out = MachineStacks.count(old, n);
    if (!simulate) {
      slots.set(slot, MachineStacks.count(old, MachineStacks.count(old) - n));
      dirty();
    }
    return out;
  }

  public void func_73660_a() {
    if (fault || !owner()) return;
    Object w = world(), p = pos();
    if (!Reflect.loaded(w, p)) return;
    try {
      if (processor) process(w, p);
      else charge();
    } catch (RuntimeException error) {
      fault = true;
      dirty();
      System.err.println("[DEAD EARTH] Machine stopped safely: " + error.getMessage());
    }
  }

  private void charge() {
    Object stack = slots.get(0);
    if (MachineStacks.empty(stack) || energy <= 0) return;
    Object item = MachineStacks.item(stack);
    int offer = Math.min(energy, cfg().chargerFEPerTick);
    if (Reflect.is(item, "com.hbm.api.energymk2.IBatteryItem")) {
      long before = HbmToolApi.charge(item, stack),
          max = HbmToolApi.maximum(item, stack),
          rate = HbmToolApi.rate(item, stack);
      if (before < 0 || max <= before || rate <= 0) return;
      long he = Math.min(Math.min(max - before, rate), (long) Math.floor(offer * cfg().hePerFE));
      if (he <= 0) return;
      HbmToolApi.accept(item, stack, he);
      long after = HbmToolApi.charge(item, stack), received = after - before;
      if (received < 0 || received > he)
        throw new IllegalStateException("Native battery charge contract changed");
      if (received > 0) {
        energy -= Math.min(offer, (int) Math.ceil(received / cfg().hePerFE));
        dirty();
      }
    } else {
      Object cap = Reflect.call(stack, "getCapability", CapabilityEnergy.ENERGY, null);
      if (cap == null) return;
      int accepted = ((Number) Reflect.call(cap, "receiveEnergy", offer, false)).intValue();
      if (accepted < 0 || accepted > offer)
        throw new IllegalStateException("FE item charge contract changed");
      if (accepted > 0) {
        energy -= accepted;
        dirty();
      }
    }
  }

  private void process(Object w, Object p) {
    Object input = slots.get(0);
    if (pending != null) {
      if (!MachineStacks.same(input, pendingInput)) {
        pending = null;
        pendingInput = null;
        progress = 0;
        dirty();
        return;
      }
      commit();
      return;
    }
    if (!MachineStacks.ore(input) || energy < cfg().processorFEPerCycle) {
      if (progress != 0) {
        progress = 0;
        dirty();
      }
      return;
    }
    if (++progress < cfg().processorCycleTicks) {
      dirty();
      return;
    }
    Object block = MachineStacks.block(input);
    int meta = ((Number) Reflect.call(input, "getMetadata/func_77960_j")).intValue();
    Object state = Reflect.call(block, "getStateFromMeta/func_176203_a", meta);
    if ((Boolean) Reflect.call(block, "hasTileEntity", state))
      throw new IllegalStateException("Tile entity ores are not supported");
    if (((Number) Reflect.call(state, "getBlockHardness/func_185887_b", w, p)).floatValue() < 0)
      throw new IllegalStateException("Unbreakable ore cannot be processed");
    // Forge's four-argument wrapper dispatches either legacy overrides or the modern NonNullList
    // overload.
    Collection<?> nativeDrops =
        (Collection<?>)
            Reflect.call(
                block, "getDrops", dropAccess(block, w, p, state), p, state, cfg().fortuneLevel);
    if (nativeDrops.size() > 64)
      throw new IllegalStateException("Native ore drop batch exceeds the bounded buffer");
    pending = new ArrayList<Object>();
    for (Object drop : nativeDrops)
      if (!MachineStacks.empty(drop)) {
        int left = MachineStacks.count(drop);
        if (left < 1 || left > 4096)
          throw new IllegalStateException("Native ore drop count exceeds the bounded buffer");
        int limit = MachineStacks.max(drop);
        if (limit < 1) throw new IllegalStateException("Invalid native ore stack limit");
        while (left > 0) {
          int n = Math.min(left, limit);
          pending.add(MachineStacks.count(drop, n));
          left -= n;
          if (pending.size() > 64)
            throw new IllegalStateException("Native ore output exceeds bounded pending slots");
        }
      }
    pendingInput = MachineStacks.count(input, 1);
    dirty();
    commit();
  }

  private void commit() {
    if (pending == null || energy < cfg().processorFEPerCycle) return;
    List<Object> result = MachineStacks.merge(slots.subList(1, slots.size()), pending);
    if (result == null) return;
    // Complete output capacity was established on copies; commit input, all outputs and FE together
    // on this thread.
    slots.set(0, MachineStacks.count(slots.get(0), MachineStacks.count(slots.get(0)) - 1));
    for (int i = 0; i < result.size(); i++) slots.set(i + 1, result.get(i));
    energy -= cfg().processorFEPerCycle;
    pending = null;
    pendingInput = null;
    progress = 0;
    dirty();
  }

  private Object dropAccess(Object block, Object w, Object p, Object state) {
    String legacy = "", modern = "";
    for (java.lang.reflect.Method m : block.getClass().getMethods())
      if (m.getName().equals("getDrops")) {
        if (m.getParameterTypes().length == 4) legacy = m.getDeclaringClass().getName();
        if (m.getParameterTypes().length == 5) modern = m.getDeclaringClass().getName();
      }
    // Pinned CE2.6.1.0 bytecode: these exact implementations read only World.rand, never
    // chunks/blocks.
    // They require a World cast. Do not extend this exception to arbitrary mod drop methods.
    if (legacy.equals("com.hbm.blocks.generic.BlockResourceStone")
        || legacy.equals("com.hbm.blocks.generic.BlockOreBasalt")
        || legacy.equals("net.minecraft.block.Block")
            && modern.equals("com.hbm.blocks.generic.BlockNTMOre")) return w;
    return safeAccess(w, p, state);
  }

  private Object safeAccess(Object w, Object target, Object oreState) {
    Class<?> api = Reflect.type("net.minecraft.world.IBlockAccess");
    return Proxy.newProxyInstance(
        api.getClassLoader(),
        new Class<?>[] {api},
        (o, m, a) -> {
          String n = m.getName();
          if (n.equals("getBlockState") || n.equals("func_180495_p")) {
            Object at = a[0];
            return at.equals(target)
                ? oreState
                : Reflect.loaded(w, at)
                    ? Reflect.state(w, at)
                    : Reflect.call(
                        Reflect.registry("BLOCKS", "minecraft:air"),
                        "getDefaultState/func_176223_P");
          }
          if (n.equals("getTileEntity") || n.equals("func_175625_s")) {
            Object at = a[0];
            return at.equals(target) || !Reflect.loaded(w, at)
                ? null
                : Reflect.call(w, "getTileEntity/func_175625_s", at);
          }
          if (n.equals("isAirBlock") || n.equals("func_175623_d")) {
            Object at = a[0];
            return !at.equals(target)
                && (!Reflect.loaded(w, at) || "minecraft:air".equals(Reflect.blockId(w, at)));
          }
          if (n.equals("getBiome") || n.equals("func_180494_b"))
            return Reflect.call(
                w, "getBiome/func_180494_b", Reflect.loaded(w, a[0]) ? a[0] : target);
          if (n.equals("getStrongPower") || n.equals("func_175627_a")) return 0;
          if (n.equals("getCombinedLight") || n.equals("func_175626_b")) return 0xf000f0;
          if (n.equals("getWorldType") || n.equals("func_175624_G"))
            return Reflect.call(w, "getWorldType/func_175624_G");
          if (n.equals("isSideSolid")) return false;
          if (n.equals("toString")) return "DeadEarthLoadedOreAccess";
          if (n.equals("hashCode")) return System.identityHashCode(o);
          if (n.equals("equals")) return o == a[0];
          throw new IllegalStateException("Unknown IBlockAccess method " + n);
        });
  }

  public void interact(Object player, Object hand) {
    if (!owner() || fault) return;
    Object held = Reflect.call(player, "getHeldItem/func_184586_b", hand);
    if (!MachineStacks.empty(held)) {
      Object rest = insert(0, held, false);
      Reflect.call(player, "setHeldItem/func_184611_a", hand, rest);
      status(player);
      return;
    }
    boolean sneak = (Boolean) Reflect.call(player, "isSneaking/func_70093_af");
    Object taken = MachineStacks.empty();
    if (processor && sneak && pending == null && !MachineStacks.empty(slots.get(0))) {
      taken = slots.get(0);
      slots.set(0, MachineStacks.empty());
      progress = 0;
      dirty();
    } else
      for (int i = processor ? 1 : 0; i < slots.size(); i++)
        if (!MachineStacks.empty(slots.get(i))) {
          taken = extract(i, 64, false);
          break;
        }
    if (!MachineStacks.empty(taken)) {
      Reflect.call(
          Reflect.get(player, "inventory/field_71071_by"),
          "addItemStackToInventory/func_70441_a",
          taken);
      if (!MachineStacks.empty(taken)) Reflect.call(player, "dropItem/func_71019_a", taken, false);
    }
    status(player);
  }

  private void status(Object player) {
    String info =
        (processor ? "Fortune " + cfg().fortuneLevel + " | native ore drops" : "FE / HBM charger")
            + " | "
            + energy
            + " FE"
            + (pending != null ? " | output blocked" : "");
    Reflect.call(
        player,
        "sendMessage/func_145747_a",
        Reflect.make("net.minecraft.util.text.TextComponentString", info));
  }

  void spill() {
    if (!owner() || preserved != null) return;
    Object w = world(), p = pos();
    List<Object> drops = new ArrayList<Object>();
    // Breaking a blocked processor settles its already rolled batch, preventing free Fortune
    // rerolls.
    if (pending != null
        && MachineStacks.same(slots.get(0), pendingInput)
        && energy >= cfg().processorFEPerCycle) {
      slots.set(0, MachineStacks.count(slots.get(0), MachineStacks.count(slots.get(0)) - 1));
      for (Object s : pending) if (!MachineStacks.empty(s)) drops.add(s);
      energy -= cfg().processorFEPerCycle;
    }
    for (Object s : slots) if (!MachineStacks.empty(s)) drops.add(s);
    for (int i = 0; i < slots.size(); i++) slots.set(i, MachineStacks.empty());
    pending = null;
    pendingInput = null;
    energy = 0;
    progress = 0;
    for (Object s : drops)
      Reflect.call(
          Reflect.type("net.minecraft.inventory.InventoryHelper"),
          "spawnItemStack/func_180173_a",
          w,
          Reflect.x(p) + .5,
          Reflect.y(p) + .5,
          Reflect.z(p) + .5,
          s);
    dirty();
  }

  public NBTTagCompound func_189515_b(NBTTagCompound tag) {
    if (preserved != null) return (NBTTagCompound) Reflect.call(preserved, "copy/func_74737_b");
    super.func_189515_b(tag);
    Reflect.call(tag, "setInteger/func_74768_a", "DeadEarthMachineSchema", 1);
    Reflect.call(tag, "setInteger/func_74768_a", "Energy", energy);
    Reflect.call(tag, "setInteger/func_74768_a", "Progress", progress);
    Reflect.call(tag, "setBoolean/func_74757_a", "Fault", fault);
    Object inventory = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (Object s : slots)
      Reflect.call(inventory, "appendTag/func_74742_a", MachineStacks.write(s));
    Reflect.call(tag, "setTag/func_74782_a", "MachineItems", inventory);
    if (pending != null) {
      Object list = Reflect.make("net.minecraft.nbt.NBTTagList");
      for (Object s : pending) Reflect.call(list, "appendTag/func_74742_a", MachineStacks.write(s));
      Reflect.call(tag, "setTag/func_74782_a", "PendingDrops", list);
      Reflect.call(tag, "setTag/func_74782_a", "PendingInput", MachineStacks.write(pendingInput));
    } else {
      Reflect.call(tag, "removeTag/func_82580_o", "PendingDrops");
      Reflect.call(tag, "removeTag/func_82580_o", "PendingInput");
    }
    return tag;
  }

  public void func_145839_a(NBTTagCompound tag) {
    super.func_145839_a(tag);
    pending = null;
    pendingInput = null;
    preserved = null;
    fault = false;
    for (int i = 0; i < slots.size(); i++) slots.set(i, MachineStacks.empty());
    try {
      int schema =
          ((Number) Reflect.call(tag, "getInteger/func_74762_e", "DeadEarthMachineSchema"))
              .intValue();
      if (schema != 0 && schema != 1
          || schema == 0 && (Boolean) Reflect.call(tag, "hasKey/func_74764_b", "MachineItems"))
        throw new IllegalArgumentException("Unknown machine schema");
      energy = ((Number) Reflect.call(tag, "getInteger/func_74762_e", "Energy")).intValue();
      progress = ((Number) Reflect.call(tag, "getInteger/func_74762_e", "Progress")).intValue();
      if (energy < 0 || energy > 100000000 || progress < 0 || progress > 12000)
        throw new IllegalArgumentException("Invalid machine state");
      fault = (Boolean) Reflect.call(tag, "getBoolean/func_74767_n", "Fault");
      Object list = Reflect.call(tag, "getTagList/func_150295_c", "MachineItems", 10);
      int size = ((Number) Reflect.call(list, "tagCount/func_74745_c")).intValue();
      if (size != 0 && size != slots.size())
        throw new IllegalArgumentException("Invalid machine slots");
      for (int i = 0; i < size; i++) {
        Object stack = MachineStacks.read(Reflect.call(list, "getCompoundTagAt/func_150305_b", i));
        if (MachineStacks.count(stack)
            > Math.min(
                i == 0 && !processor ? 1 : 64,
                MachineStacks.empty(stack) ? 64 : MachineStacks.max(stack)))
          throw new IllegalArgumentException("Invalid machine count");
        slots.set(i, stack);
      }
      if ((Boolean) Reflect.call(tag, "hasKey/func_74764_b", "PendingDrops")) {
        Object out = Reflect.call(tag, "getTagList/func_150295_c", "PendingDrops", 10);
        int n = ((Number) Reflect.call(out, "tagCount/func_74745_c")).intValue();
        if (!processor || n > 64) throw new IllegalArgumentException("Invalid pending batch");
        pending = new ArrayList<Object>();
        for (int i = 0; i < n; i++) {
          Object drop = MachineStacks.read(Reflect.call(out, "getCompoundTagAt/func_150305_b", i));
          if (MachineStacks.count(drop)
              > (MachineStacks.empty(drop) ? 64 : MachineStacks.max(drop)))
            throw new IllegalArgumentException("Invalid pending count");
          pending.add(drop);
        }
        pendingInput =
            MachineStacks.read(Reflect.call(tag, "getCompoundTag/func_74775_l", "PendingInput"));
        if (!MachineStacks.same(pendingInput, slots.get(0)))
          throw new IllegalArgumentException("Pending input changed");
      }
    } catch (RuntimeException bad) {
      preserved = (NBTTagCompound) Reflect.call(tag, "copy/func_74737_b");
      fault = true;
      System.err.println(
          "[DEAD EARTH] Machine NBT preserved after invalid state: " + bad.getMessage());
    }
  }
}
