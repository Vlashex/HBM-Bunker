package dev.deadearth.legacy.climate;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
import java.util.*;

/**
 * All climate coefficients live in the server config directory. Season length remains Campaign's
 * setting.
 */
public final class ClimateConfig {
  public boolean enabled = true,
      weather = true,
      coating = true,
      freezeWater = true,
      summerFire = true,
      exposure = true,
      clientPresentation = true;
  public int cellsPerTick = 16384,
      editsPerTick = 64,
      sectionsPerTick = 8,
      columnsPerTick = 8,
      chunkDiscoveryPerTick = 32,
      trackedChunks = 2048;
  public int weatherRefreshTicks = 100,
      weatherLockTicks = 400,
      coatingDepth = 96,
      accretionLayers = 2,
      thawLayers = 2;
  public int topIce = 3,
      topSnow = 2,
      sideIce = 2,
      sideSnow = 1,
      reliefScale = 32,
      iceRelief = 2,
      snowRelief = 1,
      sideRelief = 1;
  public int peakSpacing = 64,
      peakMinHeight = 3,
      peakMaxHeight = 9,
      peakMinRadius = 2,
      peakMaxRadius = 5;
  public double peakChance = .12;
  public boolean bareIceTips = true;
  public int operationClearance = 2,
      exposureTicks = 20,
      exposureLimit = 180,
      heatGain = 1,
      coldGain = 1,
      recovery = 3;
  public int softThreshold = 40,
      harmThreshold = 100,
      potionTicks = 60,
      potionAmplifier = 0,
      damageTicks = 100,
      igniteSeconds = 2;
  public double coldDamage = 1, summerFireChance = .0005;
  public double winterFog = .025, heatFog = .009;
  public double winterFogRed = .69,
      winterFogGreen = .76,
      winterFogBlue = .84,
      heatFogRed = .83,
      heatFogGreen = .66,
      heatFogBlue = .42;
  public int particlesPerTick = 3, particleRadius = 20, particleHeight = 12;
  public String[] protectedFragments = {
    "door",
    "hatch",
    "chest",
    "barrel",
    "crate",
    "machine",
    "relay",
    "turret",
    "control",
    "conveyor",
    "ladder",
    "lever",
    "button",
    "bed",
    "fence_gate",
    "portal"
  };
  public String[] protectiveArmor = {
    "hbm:asbestos_helmet", "hbm:asbestos_plate", "hbm:asbestos_legs", "hbm:asbestos_boots"
  };

  public static ClimateConfig load(Path file) {
    try {
      if (!Files.exists(file)) {
        ClimateConfig c = new ClimateConfig();
        JsonFiles.write(file, c);
        return c;
      }
      JsonObject raw = JsonFiles.read(file, 65536);
      for (Map.Entry<String, JsonElement> entry : raw.entrySet())
        ClimateConfig.class.getField(entry.getKey());
      ClimateConfig c =
          dev.deadearth.legacy.data.GameDataFiles.decode(
              raw, ClimateConfig.class, file.getFileName().toString());
      c.validate();
      return c;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid dead-earth/climate.json", e);
    }
  }

  public void validate() {
    range(cellsPerTick, 512, 262144);
    range(editsPerTick, 1, 512);
    range(sectionsPerTick, 1, 64);
    range(columnsPerTick, 1, 64);
    range(chunkDiscoveryPerTick, 1, 256);
    range(trackedChunks, 16, 16384);
    range(weatherRefreshTicks, 20, 1200);
    range(weatherLockTicks, 40, 4800);
    range(coatingDepth, 16, 256);
    range(accretionLayers, 1, 8);
    range(thawLayers, 1, 8);
    range(topIce, 0, 6);
    range(topSnow, 0, 4);
    range(sideIce, 0, 4);
    range(sideSnow, 0, 4);
    range(reliefScale, 8, 128);
    range(iceRelief, 0, 4);
    range(snowRelief, 0, 2);
    range(sideRelief, 0, 2);
    range(peakSpacing, 16, 256);
    range(peakMinHeight, 1, 16);
    range(peakMaxHeight, peakMinHeight, 16);
    range(peakMinRadius, 1, 8);
    range(peakMaxRadius, peakMinRadius, 8);
    fraction(peakChance, 0, 1);
    range(operationClearance, 1, 3);
    range(exposureTicks, 20, 200);
    range(exposureLimit, 1, 3600);
    range(heatGain, 0, 100);
    range(coldGain, 0, 100);
    range(recovery, 1, 100);
    range(softThreshold, 1, exposureLimit);
    range(harmThreshold, softThreshold, exposureLimit);
    range(potionTicks, 20, 1200);
    range(potionAmplifier, 0, 4);
    range(damageTicks, 20, 2400);
    range(igniteSeconds, 0, 10);
    fraction(coldDamage, 0, 20);
    fraction(summerFireChance, 0, .1);
    fraction(winterFog, 0, .1);
    fraction(heatFog, 0, .1);
    fraction(winterFogRed, 0, 1);
    fraction(winterFogGreen, 0, 1);
    fraction(winterFogBlue, 0, 1);
    fraction(heatFogRed, 0, 1);
    fraction(heatFogGreen, 0, 1);
    fraction(heatFogBlue, 0, 1);
    range(particlesPerTick, 0, 32);
    range(particleRadius, 4, 64);
    range(particleHeight, 2, 32);
    if (protectedFragments == null
        || protectedFragments.length > 128
        || protectiveArmor == null
        || protectiveArmor.length > 128) throw new IllegalArgumentException("Climate lists");
    for (String s : protectedFragments)
      if (s == null || !s.matches("[a-z0-9_]{2,64}"))
        throw new IllegalArgumentException("Protected fragment");
    for (String s : protectiveArmor)
      if (s == null || !s.matches("[a-z0-9_]+:[a-z0-9_./]+"))
        throw new IllegalArgumentException("Protective armor");
  }

  static void range(int v, int a, int b) {
    if (v < a || v > b) throw new IllegalArgumentException("Climate integer range");
  }

  static void fraction(double v, double a, double b) {
    if (!Double.isFinite(v) || v < a || v > b)
      throw new IllegalArgumentException("Climate coefficient range");
  }

  public int maximumTop() {
    return topIce + iceRelief + peakMaxHeight + topSnow + snowRelief;
  }

  public int maximumSide() {
    return sideIce + sideRelief + sideSnow;
  }
}
