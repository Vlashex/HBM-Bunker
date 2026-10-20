package dev.deadearth.legacy.sieving;

import dev.deadearth.legacy.Reflect;
import java.util.Random;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

final class SieveBlock extends BlockContainer {
  static final Object MESH =
      Reflect.call(
          Reflect.type("net.minecraft.block.properties.PropertyInteger"),
          "create/func_177719_a",
          "mesh",
          0,
          4);
  static final Object
      FILL =
          Reflect.call(
              Reflect.type("net.minecraft.block.properties.PropertyInteger"),
              "create/func_177719_a",
              "fill",
              0,
              4),
      MATERIAL =
          Reflect.call(
              Reflect.type("net.minecraft.block.properties.PropertyInteger"),
              "create/func_177719_a",
              "material",
              0,
              18);
  private final int level;

  SieveBlock(int level) {
    super(
        (Material)
            Reflect.get(
                Reflect.type("net.minecraft.block.material.Material"), "WOOD/field_151575_d"));
    this.level = level;
    LegacySieving.configure(this, "compact_sieve_v" + level, 3f);
  }

  public boolean func_180639_a(
      World w,
      BlockPos p,
      IBlockState s,
      net.minecraft.entity.player.EntityPlayer player,
      net.minecraft.util.EnumHand hand,
      net.minecraft.util.EnumFacing side,
      float hitX,
      float hitY,
      float hitZ) {
    if (!(Boolean) Reflect.get(w, "isRemote/field_72995_K")) {
      Object tile = Reflect.call(w, "getTileEntity/func_175625_s", p);
      if (tile instanceof SieveTile) ((SieveTile) tile).interact(player, hand);
    }
    return true;
  }

  protected net.minecraft.block.state.BlockStateContainer func_180661_e() {
    Object properties =
        java.lang.reflect.Array.newInstance(
            Reflect.type("net.minecraft.block.properties.IProperty"), 3);
    java.lang.reflect.Array.set(properties, 0, MESH);
    java.lang.reflect.Array.set(properties, 1, FILL);
    java.lang.reflect.Array.set(properties, 2, MATERIAL);
    return (net.minecraft.block.state.BlockStateContainer)
        Reflect.make("net.minecraft.block.state.BlockStateContainer", this, properties);
  }

  public IBlockState func_176221_a(
      IBlockState state, net.minecraft.world.IBlockAccess world, BlockPos position) {
    Object tile = Reflect.call(world, "getTileEntity/func_175625_s", position);
    SieveTile sieve = tile instanceof SieveTile ? (SieveTile) tile : null;
    state =
        (IBlockState)
            Reflect.call(
                state, "withProperty/func_177226_a", MESH, sieve == null ? 0 : sieve.visibleMesh());
    state =
        (IBlockState)
            Reflect.call(
                state, "withProperty/func_177226_a", FILL, sieve == null ? 0 : sieve.visibleFill());
    return (IBlockState)
        Reflect.call(
            state,
            "withProperty/func_177226_a",
            MATERIAL,
            sieve == null ? 0 : sieve.visibleMaterial());
  }

  public int func_176201_c(IBlockState state) {
    return 0;
  }

  public boolean func_149662_c(IBlockState state) {
    return false;
  }

  public boolean func_149686_d(IBlockState state) {
    return false;
  }

  @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
  public net.minecraft.util.BlockRenderLayer getBlockLayer() {
    return net.minecraft.util.BlockRenderLayer.CUTOUT;
  }

  public net.minecraft.util.EnumBlockRenderType func_149645_b(IBlockState s) {
    return net.minecraft.util.EnumBlockRenderType.MODEL;
  }

  public TileEntity func_149915_a(World w, int meta) {
    return level == 3 ? new SieveTile.III() : level == 4 ? new SieveTile.IV() : new SieveTile.V();
  }

  // The break hook emits exactly one packed frame; native ordinary drops must not emit a second
  // frame.
  public Item func_180660_a(IBlockState s, Random r, int fortune) {
    return (Item) Reflect.registry("ITEMS", "minecraft:air");
  }

  public void func_180663_b(World w, BlockPos p, IBlockState state) {
    if (!(Boolean) Reflect.get(w, "isRemote/field_72995_K")) {
      Object t = Reflect.call(w, "getTileEntity/func_175625_s", p);
      if (t instanceof SieveTile) ((SieveTile) t).pack();
    }
    Reflect.call(w, "removeTileEntity/func_175713_t", p);
  }
}
