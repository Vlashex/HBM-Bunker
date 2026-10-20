package dev.deadearth.legacy.materials;

import dev.deadearth.legacy.data.GameDataFiles;
import java.nio.file.Path;
import java.util.*;

/** Exact-form material policy, independent of worlds, entities and item registries. */
public final class MaterialCatalog {
  public int schema;
  public Policy policy;
  public List<Material> materials;

  public static final class Policy {
    public boolean convertTaggedStacks, convertCapabilityStacks, retainRegistryEntries;
    public int inventoryIntervalTicks, inventorySlotsPerPass;
    public List<String> forms, excludedEquivalences;
  }

  public static final class Material {
    public String material;
    public List<Form> forms;
    public Crop crop;
  }

  public static final class Form {
    public String form, canonical;
    public List<String> oreNames, legacyAliases;
    public Long value;
    public boolean allowEmc;
  }

  public static final class Crop {
    public String essence, output, nativeOreName;
    public int count, seedEnable;
    public List<String> pattern;
  }

  public static MaterialCatalog load(Path path) throws Exception {
    MaterialCatalog result = GameDataFiles.load(path, MaterialCatalog.class, 262144);
    result.validate();
    return result;
  }

  public void validate() {
    Set<String> allowed = new HashSet<String>(Arrays.asList("ingot", "dust", "nugget", "plate"));
    if (schema != 1
        || policy == null
        || policy.convertTaggedStacks
        || policy.convertCapabilityStacks
        || !policy.retainRegistryEntries
        || policy.inventoryIntervalTicks < 20
        || policy.inventoryIntervalTicks > 200
        || policy.inventorySlotsPerPass < 1
        || policy.inventorySlotsPerPass > 16
        || policy.forms == null
        || policy.forms.size() != 4
        || !new HashSet<String>(policy.forms).equals(allowed)
        || policy.excludedEquivalences == null
        || policy.excludedEquivalences.size() > 32
        || materials == null
        || materials.isEmpty()
        || materials.size() > 64)
      throw new IllegalArgumentException("Unsafe material policy/bounds");
    Set<String> names = new HashSet<String>(),
        canonical = new HashSet<String>(),
        aliases = new HashSet<String>(),
        oreGroups = new HashSet<String>();
    for (Material material : materials) {
      if (material == null
          || material.material == null
          || !material.material.matches("[a-z_]+")
          || !names.add(material.material)
          || Arrays.asList("silicon", "certus_quartz", "fuel").contains(material.material)
          || material.forms == null
          || material.forms.isEmpty()
          || material.forms.size() > 4)
        throw new IllegalArgumentException("Unsafe material name/forms");
      Set<String> forms = new HashSet<String>(), products = new HashSet<String>();
      for (Form form : material.forms) {
        if (form == null
            || !allowed.contains(form.form)
            || !forms.add(form.form)
            || !token(form.canonical)
            || !canonical.add(form.canonical)
            || form.oreNames == null
            || form.oreNames.isEmpty()
            || form.oreNames.size() > 4
            || form.legacyAliases == null
            || form.legacyAliases.size() > 32)
          throw new IllegalArgumentException("Unsafe material form");
        if (!form.canonical.startsWith("hbm:")
            && !Arrays.asList(
                    "minecraft:iron_ingot:0",
                    "minecraft:gold_ingot:0",
                    "minecraft:iron_nugget:0",
                    "minecraft:gold_nugget:0")
                .contains(form.canonical))
          throw new IllegalArgumentException("Canonical material is not HBM");
        for (String ore : form.oreNames)
          if (ore == null || !ore.matches(form.form + "[A-Z][A-Za-z0-9]*") || !oreGroups.add(ore))
            throw new IllegalArgumentException(
                "Ore group changes form or belongs to two materials");
        for (String alias : form.legacyAliases) {
          if (!token(alias)
              || alias.startsWith("hbm:")
              || alias.startsWith("minecraft:")
              || !aliases.add(alias)) throw new IllegalArgumentException("Unsafe legacy alias");
          if (alias.startsWith("exnihilocreatio:")
              && !(form.form.equals("ingot") && alias.endsWith(":3")
                  || form.form.equals("dust") && alias.endsWith(":2")))
            throw new IllegalArgumentException("Ore pieces/chunks are not equal material forms");
        }
        if (form.allowEmc
            && (form.value == null
                || !Arrays.asList("ingot", "dust").contains(form.form)
                || Arrays.asList("steel", "uranium", "thorium").contains(material.material)))
          throw new IllegalArgumentException("Unsafe raw EMC material");
        if (form.value != null && (form.value < 1 || form.value > 1000000000L))
          throw new IllegalArgumentException("Unsafe material value");
        products.add(form.canonical);
      }
      if (material.crop != null) {
        Crop crop = material.crop;
        if (crop.seedEnable != 2
            || material.material.equals("steel")
            || !products.contains(crop.output)
            || crop.essence == null
            || !crop.essence.matches("mysticalagriculture:[a-z_]+_essence")
            || crop.count < 1
            || crop.count > 64
            || crop.nativeOreName == null
            || !crop.nativeOreName.matches("(ingot|dust)[A-Z][A-Za-z0-9]*")
            || !(Arrays.asList("EEE", "E E", "EEE").equals(crop.pattern)
                || Arrays.asList("EEE", "   ", "   ").equals(crop.pattern)))
          throw new IllegalArgumentException("Unsafe crop source output");
      }
    }
    for (String alias : aliases)
      if (canonical.contains(alias))
        throw new IllegalArgumentException("Canonical item is an alias");
  }

  public static boolean token(String value) {
    if (value == null || !value.matches("[a-z0-9_]+:[a-z0-9_./]+:[0-9]{1,5}")) return false;
    return Integer.parseInt(value.substring(value.lastIndexOf(':') + 1)) < 32767;
  }
}
