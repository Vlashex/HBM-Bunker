package dev.deadearth.legacy.sieving;

import dev.deadearth.legacy.Reflect;
import net.minecraft.item.Item;

final class SieveMesh extends Item {
  SieveMesh(String id) {
    super();
    Reflect.call(this, "setRegistryName", "dead_earth", id);
    Reflect.call(this, "setUnlocalizedName/func_77655_b", "dead_earth." + id);
    Reflect.call(this, "setMaxStackSize/func_77625_d", 64);
    Reflect.call(
        this,
        "setCreativeTab/func_77637_a",
        Reflect.get(
            Reflect.type("net.minecraft.creativetab.CreativeTabs"), "MATERIALS/field_78035_l"));
  }
}
