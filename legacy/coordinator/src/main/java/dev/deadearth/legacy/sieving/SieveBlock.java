package dev.deadearth.legacy.sieving;
import dev.deadearth.legacy.Reflect;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.Random;
final class SieveBlock extends BlockContainer {
 private final int level;
 SieveBlock(int level){super((Material)Reflect.get(Reflect.type("net.minecraft.block.material.Material"),"WOOD/field_151575_d"));this.level=level;LegacySieving.configure(this,"compact_sieve_v"+level,3f);}
 public boolean func_180639_a(World w,BlockPos p,IBlockState s,net.minecraft.entity.player.EntityPlayer player,net.minecraft.util.EnumHand hand,net.minecraft.util.EnumFacing side,float hitX,float hitY,float hitZ){if(!(Boolean)Reflect.get(w,"isRemote/field_72995_K")){Object tile=Reflect.call(w,"getTileEntity/func_175625_s",p);if(tile instanceof SieveTile)((SieveTile)tile).interact(player,hand);}return true;}
 public net.minecraft.util.EnumBlockRenderType func_149645_b(IBlockState s){return net.minecraft.util.EnumBlockRenderType.MODEL;}
 public TileEntity func_149915_a(World w,int meta){return level==3?new SieveTile.III():level==4?new SieveTile.IV():new SieveTile.V();}
 // The break hook emits exactly one packed frame; native ordinary drops must not emit a second frame.
 public Item func_180660_a(IBlockState s,Random r,int fortune){return (Item)Reflect.registry("ITEMS","minecraft:air");}
 public void func_180663_b(World w,BlockPos p,IBlockState state){if(!(Boolean)Reflect.get(w,"isRemote/field_72995_K")){Object t=Reflect.call(w,"getTileEntity/func_175625_s",p);if(t instanceof SieveTile)((SieveTile)t).pack();}Reflect.call(w,"removeTileEntity/func_175713_t",p);}
}
