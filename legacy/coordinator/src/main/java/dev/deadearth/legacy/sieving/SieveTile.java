package dev.deadearth.legacy.sieving;

import dev.deadearth.legacy.Reflect;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;

/** Four manual strokes authorize one compressed input; native rolls retain a durable cursor. */
public abstract class SieveTile extends TileEntity {
  private static final int BANK_TYPES = 512;
  private final int level;
  private final List<Object> slots = new ArrayList<Object>();
  private final List<Reward> bank = new ArrayList<Reward>();
  private Object handler, automatedHandler;
  private int visualMesh,
      visualFill,
      visualMaterial,
      lastVisualMesh = -1,
      lastVisualFill = -1,
      lastVisualMaterial = -1;
  private String raw = "", deliveryPlayer = "";
  private int grade, remaining, cursor, manualStrokes;
  private long seed, draws, completed;
  private List<Object> pending;
  private boolean fault, packed, migrated;
  private NBTTagCompound preserved;

  private static final class Reward {
    final Object prototype;
    int amount;

    Reward(Object stack, int count) {
      prototype = SieveStacks.count(stack, 1);
      amount = count;
    }
  }

  protected SieveTile(int level) {
    this.level = level;
    for (int i = 0; i < 29; i++) slots.add(SieveStacks.empty());
  }

  public static final class III extends SieveTile {
    public III() {
      super(3);
    }
  }

  public static final class IV extends SieveTile {
    public IV() {
      super(4);
    }
  }

  public static final class V extends SieveTile {
    public V() {
      super(5);
    }
  }

  public void onLoad() {
    if (owner()) {
      LegacySieving.track(this);
      if (migrated) {
        migrated = false;
        dirty();
      }
    }
  }

  public void func_145843_s() {
    if (owner()) LegacySieving.untrack(this);
    super.func_145843_s();
  }

  public void onChunkUnload() {
    if (owner()) LegacySieving.untrack(this);
    super.onChunkUnload();
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

  private boolean active() {
    return !packed
        && !(Boolean) Reflect.call(this, "isInvalid/func_145837_r")
        && Reflect.loaded(world(), pos())
        && Reflect.call(world(), "getTileEntity/func_175625_s", pos()) == this;
  }

  private boolean busy() {
    return !raw.isEmpty();
  }

  private boolean rewards() {
    if (!bank.isEmpty()) return true;
    for (int i = 2; i < 29; i++) if (!SieveStacks.empty(slots.get(i))) return true;
    return false;
  }

  private void dirty() {
    Reflect.call(this, "markDirty/func_70296_d");
    int m = visibleMesh(), f = visibleFill(), material = visibleMaterial();
    if (m != lastVisualMesh || f != lastVisualFill || material != lastVisualMaterial) {
      lastVisualMesh = m;
      lastVisualFill = f;
      lastVisualMaterial = material;
      Object w = world(), p = pos();
      if (w != null && Reflect.loaded(w, p)) {
        Object state = Reflect.state(w, p);
        Reflect.call(w, "notifyBlockUpdate/func_184138_a", p, state, state, 3);
      }
    }
  }

  private boolean remote() {
    Object w = world();
    return w != null && (Boolean) Reflect.get(w, "isRemote/field_72995_K");
  }

  int visibleMesh() {
    return remote() ? visualMesh : mesh(slots.get(1));
  }

  int visibleFill() {
    if (remote()) return visualFill;
    if (fault) return 0;
    if (busy()) return 4 - manualStrokes;
    return material(slots.get(0)) == null ? 0 : 4;
  }

  int visibleMaterial() {
    if (remote()) return visualMaterial;
    String name = material(slots.get(0));
    if (busy())
      for (Map.Entry<String, String> entry : LegacySieving.RAW.entrySet())
        if (entry.getValue().equals(raw)) {
          name = entry.getKey();
          break;
        }
    int index = 0;
    for (String entry : LegacySieving.RAW.keySet()) {
      if (entry.equals(name)) return index < 19 ? index : 0;
      index++;
    }
    return 0;
  }

  /** Only the four visible fill levels cross the client boundary, never the reward bank. */
  public NBTTagCompound func_189517_E_() {
    NBTTagCompound tag = super.func_189515_b(new NBTTagCompound());
    Reflect.call(tag, "setInteger/func_74768_a", "VisibleMesh", visibleMesh());
    Reflect.call(tag, "setInteger/func_74768_a", "VisibleFill", visibleFill());
    Reflect.call(tag, "setInteger/func_74768_a", "VisibleMaterial", visibleMaterial());
    return tag;
  }

  public net.minecraft.network.play.server.SPacketUpdateTileEntity func_189518_D_() {
    return (net.minecraft.network.play.server.SPacketUpdateTileEntity)
        Reflect.make(
            "net.minecraft.network.play.server.SPacketUpdateTileEntity",
            pos(),
            0,
            func_189517_E_());
  }

  public void handleUpdateTag(NBTTagCompound tag) {
    visualMesh = Math.max(0, Math.min(4, num(tag, "VisibleMesh")));
    visualFill = Math.max(0, Math.min(4, num(tag, "VisibleFill")));
    visualMaterial = Math.max(0, Math.min(18, num(tag, "VisibleMaterial")));
    Object w = world();
    if (w != null) Reflect.call(w, "markBlockRangeForRenderUpdate/func_175704_b", pos(), pos());
  }

  public void onDataPacket(
      net.minecraft.network.NetworkManager network,
      net.minecraft.network.play.server.SPacketUpdateTileEntity packet) {
    handleUpdateTag((NBTTagCompound) Reflect.call(packet, "getNbtCompound/func_148857_g"));
  }

  private int mesh(Object stack) {
    String id = SieveStacks.id(stack);
    for (int i = 0; i < LegacySieving.GRADES.length; i++)
      if (id.equals("dead_earth:compact_mesh_" + LegacySieving.GRADES[i] + "_v" + level))
        return i + 1;
    return 0;
  }

  private String material(Object stack) {
    String id = SieveStacks.id(stack);
    for (String name : LegacySieving.RAW.keySet())
      if (id.equals("dead_earth:" + LegacySieving.compressed(name, level - 1))) return name;
    return null;
  }

  private boolean valid(int slot, Object stack) {
    if (fault || SieveStacks.empty(stack) || busy()) return false;
    if (slot == 1) return mesh(stack) > 0;
    String name = material(stack);
    int installed = mesh(slots.get(1));
    return slot == 0
        && !rewards()
        && name != null
        && installed > 0
        && SieveApi.eligible(LegacySieving.RAW.get(name), installed);
  }

  private void check(int slot) {
    if (slot < 0 || slot >= slots.size()) throw new IllegalArgumentException("Compact sieve slot");
  }

  public boolean hasCapability(Capability<?> c, EnumFacing f) {
    return c == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(c, f);
  }

  @SuppressWarnings("unchecked")
  public <T> T getCapability(Capability<T> c, EnumFacing f) {
    if (c == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return (T) handler(f != null);
    return super.getCapability(c, f);
  }

  private Object handler(boolean automated) {
    Object cached = automated ? automatedHandler : handler;
    if (cached != null) return cached;
    Class<?> api = Reflect.type("net.minecraftforge.items.IItemHandler");
    Object created =
        Proxy.newProxyInstance(
            api.getClassLoader(),
            new Class<?>[] {api},
            (o, m, a) -> {
              String n = m.getName();
              if (n.equals("getSlots")) return slots.size();
              if (n.equals("getSlotLimit")) {
                check((Integer) a[0]);
                return (Integer) a[0] < 2 ? 1 : 64;
              }
              if (n.equals("getStackInSlot")) {
                int i = (Integer) a[0];
                check(i);
                return i >= 2 && busy() ? SieveStacks.empty() : SieveStacks.copy(slots.get(i));
              }
              if (n.equals("isItemValid")) {
                check((Integer) a[0]);
                return valid((Integer) a[0], a[1]);
              }
              if (n.equals("insertItem")) return insert((Integer) a[0], a[1], (Boolean) a[2]);
              if (n.equals("extractItem")) {
                check((Integer) a[0]);
                return automated && (Integer) a[0] < 2
                    ? SieveStacks.empty()
                    : extract((Integer) a[0], (Integer) a[1], (Boolean) a[2]);
              }
              if (n.equals("toString")) return "DeadEarthCompactSieveInventory";
              if (n.equals("hashCode")) return System.identityHashCode(o);
              if (n.equals("equals")) return o == a[0];
              return null;
            });
    if (automated) automatedHandler = created;
    else handler = created;
    return created;
  }

  private Object insert(int slot, Object stack, boolean simulate) {
    check(slot);
    if (SieveStacks.empty(stack)) return SieveStacks.empty();
    if (!owner()
        || !active()
        || !valid(slot, stack)
        || SieveStacks.count(stack) > SieveStacks.max(stack)) return SieveStacks.copy(stack);
    Object old = slots.get(slot);
    if (!SieveStacks.empty(old) && !SieveStacks.same(old, stack)) return SieveStacks.copy(stack);
    int n = Math.min(SieveStacks.count(stack), Math.max(0, 1 - SieveStacks.count(old)));
    if (n > 0 && !simulate) {
      slots.set(slot, SieveStacks.count(stack, SieveStacks.count(old) + n));
      dirty();
    }
    return SieveStacks.count(stack, SieveStacks.count(stack) - n);
  }

  private Object extract(int slot, int amount, boolean simulate) {
    check(slot);
    if (!owner()
        || !active()
        || fault
        || amount <= 0
        || busy()
        || slot == 1 && !SieveStacks.empty(slots.get(0))) return SieveStacks.empty();
    Object old = slots.get(slot);
    int n = Math.min(Math.min(amount, SieveStacks.max(old)), SieveStacks.count(old));
    Object out = SieveStacks.count(old, n);
    if (n > 0 && !simulate) {
      slots.set(slot, SieveStacks.count(old, SieveStacks.count(old) - n));
      dirty();
    }
    return out;
  }

  private boolean begin() {
    if (busy() || rewards()) return false;
    String name = material(slots.get(0));
    int g = mesh(slots.get(1));
    if (name == null || g == 0 || !SieveApi.eligible(LegacySieving.RAW.get(name), g)) return false;
    raw = LegacySieving.RAW.get(name);
    grade = g;
    remaining = LegacySieving.equivalents(level);
    cursor = 0;
    manualStrokes = 0;
    seed = ((Random) Reflect.get(world(), "rand/field_73012_v")).nextLong();
    slots.set(0, SieveStacks.count(slots.get(0), SieveStacks.count(slots.get(0)) - 1));
    return true;
  }

  /** Only manual activation starts a batch. Tick work computes its loot, never another batch. */
  boolean step() {
    if (fault || !owner() || !active()) return false;
    boolean attempted = false;
    try {
      if (!busy()) {
        flushBank();
        deliver();
        return false;
      }
      if (pending != null) {
        acceptPending();
        finish(null);
        return false;
      }
      if (remaining == 0) {
        finish(null);
        return false;
      }
      attempted = true;
      pending = SieveApi.draw(raw, grade, seed, cursor);
      draws++;
      dirty();
      acceptPending();
      finish(null);
      return true;
    } catch (RuntimeException error) {
      fault = true;
      dirty();
      System.err.println("[DEAD EARTH] Compact sieve stopped safely: " + error.getMessage());
      return attempted;
    }
  }

  /** Stage the whole draw before committing its saved cursor; no reroll on overflow/restart. */
  private List<Reward> combined(List<Object> additions) {
    List<Reward> next = new ArrayList<Reward>();
    for (Reward r : bank) next.add(new Reward(r.prototype, r.amount));
    for (Object stack : additions) {
      int n = SieveStacks.count(stack);
      Reward found = null;
      for (Reward r : next)
        if (SieveStacks.same(r.prototype, stack)) {
          found = r;
          break;
        }
      if (found != null) found.amount = Math.addExact(found.amount, n);
      else {
        if (next.size() >= BANK_TYPES) throw new IllegalStateException("Sieve reward type bound");
        next.add(new Reward(stack, n));
      }
    }
    long total = 0;
    for (Reward r : next) total += r.amount;
    if (total > maxRewards()) throw new IllegalStateException("Sieve reward amount bound");
    return next;
  }

  private void acceptPending() {
    if (pending == null) return;
    List<Reward> next = combined(pending);
    bank.clear();
    bank.addAll(next);
    pending = null;
    remaining--;
    cursor++;
    dirty();
  }

  private long maxRewards() {
    return (long) LegacySieving.equivalents(level) * 256 * 64;
  }

  private void finish(Object actor) {
    if (!busy() || manualStrokes != 4 || remaining != 0 || pending != null) return;
    completed++;
    raw = "";
    grade = 0;
    manualStrokes = 0;
    dirty();
    flushBank();
    if (actor != null) {
      deliveryPlayer = "";
      claim(actor);
    } else deliver();
  }

  /** Publish only a completed four-stroke batch, in legal native stacks of at most64. */
  private void flushBank() {
    if (busy() || bank.isEmpty()) return;
    boolean changed = false;
    Iterator<Reward> entries = bank.iterator();
    while (entries.hasNext()) {
      Reward reward = entries.next();
      for (int i = 2; i < 29 && reward.amount > 0; i++)
        if (SieveStacks.same(reward.prototype, slots.get(i))) {
          int n =
              Math.min(
                  reward.amount,
                  SieveStacks.max(reward.prototype) - SieveStacks.count(slots.get(i)));
          if (n > 0) {
            slots.set(i, SieveStacks.count(reward.prototype, SieveStacks.count(slots.get(i)) + n));
            reward.amount -= n;
            changed = true;
          }
        }
      for (int i = 2; i < 29 && reward.amount > 0; i++)
        if (SieveStacks.empty(slots.get(i))) {
          int n = Math.min(reward.amount, SieveStacks.max(reward.prototype));
          slots.set(i, SieveStacks.count(reward.prototype, n));
          reward.amount -= n;
          changed = true;
        }
      if (reward.amount == 0) entries.remove();
    }
    if (changed) dirty();
  }

  /**
   * Transfer every fitting stack together. A full inventory leaves the remainder safely in the
   * sieve.
   */
  private void claim(Object player) {
    if (busy() || fault) return;
    Object inventory =
        Reflect.make(
            "net.minecraftforge.items.wrapper.PlayerMainInvWrapper",
            Reflect.get(player, "inventory/field_71071_by"));
    for (int pass = 0; pass < 256; pass++) {
      flushBank();
      boolean moved = false;
      for (int i = 2; i < 29; i++)
        if (!SieveStacks.empty(slots.get(i))) {
          Object take = SieveStacks.copy(slots.get(i));
          int before = SieveStacks.count(take);
          Object leftover =
              Reflect.call(
                  Reflect.type("net.minecraftforge.items.ItemHandlerHelper"),
                  "insertItemStacked",
                  inventory,
                  take,
                  false);
          int accepted = before - SieveStacks.count(leftover);
          if (accepted > 0) {
            slots.set(i, SieveStacks.count(slots.get(i), before - accepted));
            moved = true;
            dirty();
          }
        }
      if (!moved) break;
    }
  }

  private void deliver() {
    if (busy() || deliveryPlayer.isEmpty()) return;
    String recipient = deliveryPlayer;
    deliveryPlayer = "";
    dirty();
    Object server = Reflect.call(world(), "getMinecraftServer/func_73046_m");
    Object player =
        Reflect.call(
            Reflect.call(server, "getPlayerList/func_184103_al"),
            "getPlayerByUUID/func_177451_a",
            UUID.fromString(recipient));
    if (player != null
        && Reflect.world(player) == world()
        && ((Number)
                    Reflect.call(
                        player,
                        "getDistanceSq/func_70092_e",
                        Reflect.x(pos()) + .5,
                        Reflect.y(pos()) + .5,
                        Reflect.z(pos()) + .5))
                .doubleValue()
            <= 64) claim(player);
  }

  public void interact(Object player, Object hand) {
    if (!owner() || !active() || fault) return;
    Object held = Reflect.call(player, "getHeldItem/func_184586_b", hand);
    if ((Boolean) Reflect.call(player, "isSneaking/func_70093_af")) {
      if (SieveStacks.empty(held) && !busy()) {
        Object take = extract(!SieveStacks.empty(slots.get(0)) ? 0 : 1, 64, false);
        if (!SieveStacks.empty(take)) {
          Object inventory =
              Reflect.make(
                  "net.minecraftforge.items.wrapper.PlayerMainInvWrapper",
                  Reflect.get(player, "inventory/field_71071_by"));
          take =
              Reflect.call(
                  Reflect.type("net.minecraftforge.items.ItemHandlerHelper"),
                  "insertItemStacked",
                  inventory,
                  take,
                  false);
          if (!SieveStacks.empty(take)) Reflect.call(player, "dropItem/func_71019_a", take, false);
        }
      }
      return;
    }
    if (!busy() && rewards()) {
      claim(player);
      return;
    }
    if (!busy() && !SieveStacks.empty(held) && mesh(held) > 0) {
      Reflect.call(player, "setHeldItem/func_184611_a", hand, insert(1, held, false));
      return;
    }
    if (!busy() && SieveStacks.empty(slots.get(0))) {
      if (!SieveStacks.empty(held))
        Reflect.call(player, "setHeldItem/func_184611_a", hand, insert(0, held, false));
      return;
    }
    if (!busy() && !begin()) return;
    if (manualStrokes >= 4) return;
    manualStrokes++;
    if (manualStrokes == 4)
      deliveryPlayer = String.valueOf(Reflect.call(player, "getUniqueID/func_110124_au"));
    dirty();
    finish(player);
  }

  /** Breaking moves all escrow and the receipt into exactly one placeable frame. */
  void pack() {
    if (packed || !owner()) return;
    packed = true;
    Object stack = SieveStacks.make("dead_earth:compact_sieve_v" + level, 1);
    boolean contents = busy() || pending != null || !bank.isEmpty() || fault || preserved != null;
    for (Object s : slots) contents |= !SieveStacks.empty(s);
    if (contents) {
      Object tag = Reflect.make("net.minecraft.nbt.NBTTagCompound"),
          state = func_189515_b(new NBTTagCompound());
      Reflect.call(tag, "setTag/func_74782_a", "BlockEntityTag", state);
      Reflect.call(stack, "setTagCompound/func_77982_d", tag);
    }
    Object p = pos();
    Reflect.call(
        Reflect.type("net.minecraft.inventory.InventoryHelper"),
        "spawnItemStack/func_180173_a",
        world(),
        Reflect.x(p) + .5,
        Reflect.y(p) + .5,
        Reflect.z(p) + .5,
        stack);
    LegacySieving.untrack(this);
  }

  public NBTTagCompound func_189515_b(NBTTagCompound tag) {
    if (preserved != null) return (NBTTagCompound) Reflect.call(preserved, "copy/func_74737_b");
    super.func_189515_b(tag);
    Reflect.call(tag, "setInteger/func_74768_a", "DeadEarthSieveSchema", 2);
    Reflect.call(tag, "setInteger/func_74768_a", "Level", level);
    Reflect.call(tag, "setInteger/func_74768_a", "Remaining", remaining);
    Reflect.call(tag, "setInteger/func_74768_a", "Cursor", cursor);
    Reflect.call(tag, "setInteger/func_74768_a", "Grade", grade);
    Reflect.call(tag, "setInteger/func_74768_a", "ManualStrokes", manualStrokes);
    Reflect.call(tag, "setLong/func_74772_a", "Seed", seed);
    Reflect.call(tag, "setLong/func_74772_a", "NativeDraws", draws);
    Reflect.call(tag, "setLong/func_74772_a", "CompletedBatches", completed);
    Reflect.call(tag, "setString/func_74778_a", "Raw", raw);
    Reflect.call(tag, "setString/func_74778_a", "DeliveryPlayer", deliveryPlayer);
    Reflect.call(tag, "setBoolean/func_74757_a", "Fault", fault);
    Object list = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (Object s : slots) Reflect.call(list, "appendTag/func_74742_a", SieveStacks.write(s));
    Reflect.call(tag, "setTag/func_74782_a", "SieveItems", list);
    Object rewards = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (Reward r : bank) {
      Object row = Reflect.make("net.minecraft.nbt.NBTTagCompound");
      Reflect.call(row, "setTag/func_74782_a", "Stack", SieveStacks.write(r.prototype));
      Reflect.call(row, "setInteger/func_74768_a", "Amount", r.amount);
      Reflect.call(rewards, "appendTag/func_74742_a", row);
    }
    Reflect.call(tag, "setTag/func_74782_a", "RewardBank", rewards);
    Reflect.call(tag, "setBoolean/func_74757_a", "HasPending", pending != null);
    if (pending != null) {
      Object out = Reflect.make("net.minecraft.nbt.NBTTagList");
      for (Object s : pending) Reflect.call(out, "appendTag/func_74742_a", SieveStacks.write(s));
      Reflect.call(tag, "setTag/func_74782_a", "Pending", out);
    } else Reflect.call(tag, "removeTag/func_82580_o", "Pending");
    return tag;
  }

  public void func_145839_a(NBTTagCompound tag) {
    super.func_145839_a(tag);
    preserved = null;
    packed = false;
    fault = false;
    migrated = false;
    pending = null;
    remaining = cursor = grade = manualStrokes = 0;
    raw = deliveryPlayer = "";
    seed = draws = completed = 0;
    bank.clear();
    for (int i = 0; i < 29; i++) slots.set(i, SieveStacks.empty());
    try {
      int schema = num(tag, "DeadEarthSieveSchema");
      if (schema == 0) {
        if ((Boolean) Reflect.call(tag, "hasKey/func_74764_b", "SieveItems"))
          throw new IllegalArgumentException("Missing compact sieve schema");
        return;
      }
      if ((schema != 1 && schema != 2) || num(tag, "Level") != level)
        throw new IllegalArgumentException("Unknown compact sieve schema/level");
      Object list = Reflect.call(tag, "getTagList/func_150295_c", "SieveItems", 10);
      if (((Number) Reflect.call(list, "tagCount/func_74745_c")).intValue() != 29)
        throw new IllegalArgumentException("Invalid compact sieve slots");
      // Read old stacks intact first, then refund their surplus without consuming or losing blocks.
      for (int i = 0; i < 29; i++) {
        Object s = SieveStacks.read(Reflect.call(list, "getCompoundTagAt/func_150305_b", i));
        if (SieveStacks.count(s) > Math.min(i == 1 ? 1 : 64, SieveStacks.max(s)))
          throw new IllegalArgumentException("Invalid compact stack");
        slots.set(i, s);
      }
      remaining = num(tag, "Remaining");
      cursor = num(tag, "Cursor");
      grade = num(tag, "Grade");
      seed = ((Number) Reflect.call(tag, "getLong/func_74763_f", "Seed")).longValue();
      draws = ((Number) Reflect.call(tag, "getLong/func_74763_f", "NativeDraws")).longValue();
      completed =
          ((Number) Reflect.call(tag, "getLong/func_74763_f", "CompletedBatches")).longValue();
      raw = String.valueOf(Reflect.call(tag, "getString/func_74779_i", "Raw"));
      fault = (Boolean) Reflect.call(tag, "getBoolean/func_74767_n", "Fault");
      manualStrokes = schema == 1 ? (busy() ? 4 : 0) : num(tag, "ManualStrokes");
      if (remaining < 0
          || remaining > LegacySieving.equivalents(level)
          || cursor < 0
          || cursor > LegacySieving.equivalents(level)
          || draws < 0
          || completed < 0
          || manualStrokes < 0
          || manualStrokes > 4) throw new IllegalArgumentException("Invalid compact batch cursor");
      if (busy()
          && (cursor + remaining != LegacySieving.equivalents(level)
              || grade < 1
              || grade > 4
              || mesh(slots.get(1)) != grade
              || !LegacySieving.RAW.containsValue(raw)
              || manualStrokes < 1))
        throw new IllegalArgumentException("Invalid active compact batch");
      if (!busy() && (remaining != 0 || grade != 0 || manualStrokes != 0))
        throw new IllegalArgumentException("Invalid idle compact batch");
      if (schema == 2) {
        deliveryPlayer =
            String.valueOf(Reflect.call(tag, "getString/func_74779_i", "DeliveryPlayer"));
        if (!deliveryPlayer.isEmpty()
            && !UUID.fromString(deliveryPlayer).toString().equals(deliveryPlayer))
          throw new IllegalArgumentException("Invalid delivery UUID");
        Object rewards = Reflect.call(tag, "getTagList/func_150295_c", "RewardBank", 10);
        int n = ((Number) Reflect.call(rewards, "tagCount/func_74745_c")).intValue();
        if (n > BANK_TYPES) throw new IllegalArgumentException("Reward bank type bound");
        long total = 0;
        for (int i = 0; i < n; i++) {
          Object row = Reflect.call(rewards, "getCompoundTagAt/func_150305_b", i),
              stack = SieveStacks.read(Reflect.call(row, "getCompoundTag/func_74775_l", "Stack"));
          int amount = num(row, "Amount");
          if (SieveStacks.count(stack) != 1 || amount < 1)
            throw new IllegalArgumentException("Invalid bank reward");
          for (Reward old : bank)
            if (SieveStacks.same(old.prototype, stack))
              throw new IllegalArgumentException("Duplicate bank reward");
          bank.add(new Reward(stack, amount));
          total += amount;
        }
        if (total > maxRewards()) throw new IllegalArgumentException("Reward bank amount bound");
      }
      if ((Boolean) Reflect.call(tag, "getBoolean/func_74767_n", "HasPending")) {
        if (!busy() || remaining == 0)
          throw new IllegalArgumentException("Pending draw missing receipt");
        Object out = Reflect.call(tag, "getTagList/func_150295_c", "Pending", 10);
        int n = ((Number) Reflect.call(out, "tagCount/func_74745_c")).intValue();
        if (n > 256) throw new IllegalArgumentException("Pending draw bound");
        pending = new ArrayList<Object>();
        for (int i = 0; i < n; i++) {
          Object s = SieveStacks.read(Reflect.call(out, "getCompoundTagAt/func_150305_b", i));
          if (SieveStacks.empty(s) || SieveStacks.count(s) > SieveStacks.max(s))
            throw new IllegalArgumentException("Invalid pending stack");
          pending.add(s);
        }
      }
      int allowed = mesh(slots.get(1)) > 0 ? 1 : 0, queued = SieveStacks.count(slots.get(0));
      if (queued > allowed) {
        List<Reward> refunded =
            combined(Collections.singletonList(SieveStacks.count(slots.get(0), queued - allowed)));
        bank.clear();
        bank.addAll(refunded);
        slots.set(0, SieveStacks.count(slots.get(0), allowed));
        migrated = true;
      }
      migrated |= schema == 1;
    } catch (RuntimeException bad) {
      migrated = false;
      preserved = (NBTTagCompound) Reflect.call(tag, "copy/func_74737_b");
      fault = true;
      System.err.println("[DEAD EARTH] Compact sieve NBT preserved: " + bad.getMessage());
    }
  }

  private static int num(Object tag, String key) {
    return ((Number) Reflect.call(tag, "getInteger/func_74762_e", key)).intValue();
  }
}
