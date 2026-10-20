package dev.deadearth.legacy;

import dev.deadearth.legacy.kernel.*;
import java.util.*;

/** Damage events are observations; only next-tick health deltas and committed deaths count. */
final class CombatLedger {
  private final LegacyRuntime runtime;

  CombatLedger(LegacyRuntime runtime) {
    this.runtime = runtime;
  }

  private final Map<String, Object> pendingDeaths = new LinkedHashMap<String, Object>();
  private final Map<String, Object> pendingKills = new LinkedHashMap<String, Object>();
  private final Map<String, PendingLoss> pendingLosses = new LinkedHashMap<String, PendingLoss>();

  private static final class PendingLoss {
    Object entity;
    double before;
    double pending = 0;
    boolean death = false;
    String operation;

    PendingLoss(Object e, double h, String op) {
      entity = e;
      before = h;
      operation = op;
    }
  }

  void record(Object event, boolean death) {
    Object e = Reflect.call(event, "getEntityLiving");
    if (!Reflect.is(e, "net.minecraft.entity.player.EntityPlayer")) {
      if (death) {
        String dead = Reflect.call(e, "getUniqueID/func_110124_au").toString();
        if (runtime.campaign.owned.contains(dead)) pendingDeaths.put(dead, e);
        Object killer =
            Reflect.call(Reflect.call(event, "getSource"), "getTrueSource/func_76346_g");
        if (killer != null
            && Reflect.is(killer, "net.minecraft.entity.player.EntityPlayer")
            && pendingKills.size() < 256)
          pendingKills.put(
              Reflect.call(killer, "getUniqueID/func_110124_au").toString() + "/" + dead, e);
      }
      return;
    }
    String id = Reflect.call(e, "getUniqueID/func_110124_au").toString();
    PendingLoss loss = pendingLosses.get(id);
    if (loss == null) {
      loss =
          new PendingLoss(
              e,
              ((Number) Reflect.call(e, "getHealth/func_110143_aJ")).doubleValue(),
              nearestOperation(e));
      pendingLosses.put(id, loss);
    }
    if (death) loss.death = true;
    else loss.pending += ((Number) Reflect.call(event, "getAmount")).doubleValue();
  }

  private String nearestOperation(Object player) {
    String selected = null;
    double best = 9216;
    for (CinematicEngine.Operation op : runtime.engine.operations()) {
      Campaign.Zone z = runtime.world.zone(op.zoneId());
      if (z == null || z.dimension != Reflect.dimension(Reflect.world(player))) continue;
      double dist = GameWorld.distance(Reflect.position(player), z);
      if (dist < best) {
        best = dist;
        selected = op.id().toString();
      }
    }
    return selected;
  }

  @SuppressWarnings("unchecked")
  void flush() {
    for (Map.Entry<String, Object> e : pendingDeaths.entrySet())
      if (!(Boolean) Reflect.call(e.getValue(), "isEntityAlive/func_70089_S")) {
        runtime.campaign.owned.remove(e.getKey());
        runtime.campaign.ownership.remove(e.getKey());
      }
    pendingDeaths.clear();
    for (Map.Entry<String, Object> e : pendingKills.entrySet())
      if (!(Boolean) Reflect.call(e.getValue(), "isEntityAlive/func_70089_S"))
        runtime.campaign.players.computeIfAbsent(
                e.getKey().substring(0, 36), k -> new Campaign.Player())
            .kills++;
    pendingKills.clear();
    for (Map.Entry<String, PendingLoss> entry : pendingLosses.entrySet()) {
      PendingLoss loss = entry.getValue();
      double after = ((Number) Reflect.call(loss.entity, "getHealth/func_110143_aJ")).doubleValue();
      double hp = Math.min(loss.pending, Math.max(0, loss.before - after));
      boolean died =
          loss.death && !(Boolean) Reflect.call(loss.entity, "isEntityAlive/func_70089_S");
      if (hp <= 0 && !died) continue;
      Campaign.Player stats =
          runtime.campaign.players.computeIfAbsent(entry.getKey(), k -> new Campaign.Player());
      stats.damage += hp;
      if (died) stats.deaths++;
      stats.lastLoss = runtime.campaign.activeTicks;
      if (loss.operation != null) {
        Map<String, Object> a = runtime.analytics(loss.operation),
            damage = (Map<String, Object>) a.get("damage");
        damage.put("hp", ((Number) damage.get("hp")).doubleValue() + hp);
        damage.put(
            "spatialAttributedHp", ((Number) damage.get("spatialAttributedHp")).doubleValue() + hp);
        damage.put("deaths", ((Number) damage.get("deaths")).intValue() + (died ? 1 : 0));
        damage.put(
            "points",
            ((Number) damage.get("hp")).doubleValue()
                + 20 * ((Number) damage.get("deaths")).doubleValue());
        runtime.timeline(
            loss.operation,
            died ? "death" : "hurt",
            "approach",
            "confirmed_health_delta",
            died ? 1 : 0);
      }
    }
    pendingLosses.clear();
  }
}
