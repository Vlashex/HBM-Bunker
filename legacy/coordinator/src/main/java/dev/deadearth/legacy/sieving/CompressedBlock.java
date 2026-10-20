package dev.deadearth.legacy.sieving;
import dev.deadearth.legacy.Reflect;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
/** Stable placeable storage block; it never becomes falling sand or receives an invented sieve multiplier. */
final class CompressedBlock extends Block {
 CompressedBlock(String id,int depth){super((Material)Reflect.get(Reflect.type("net.minecraft.block.material.Material"),"ROCK/field_151576_e"));LegacySieving.configure(this,id,2f+depth);Reflect.call(this,"setHarvestLevel","pickaxe",0);}
}
