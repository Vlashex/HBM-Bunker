package net.minecraft.block;
public class Block extends net.minecraftforge.registries.IForgeRegistryEntry.Impl<Block> {
    public Block(){}
    protected Block(net.minecraft.block.material.Material material){}
    public boolean canEntityDestroy(net.minecraft.block.state.IBlockState state,net.minecraft.world.IBlockAccess world,net.minecraft.util.math.BlockPos pos,net.minecraft.entity.Entity entity){throw new UnsupportedOperationException("compile-only Forge API");}
    public void func_180663_b(net.minecraft.world.World world,net.minecraft.util.math.BlockPos position,net.minecraft.block.state.IBlockState state){}
    public net.minecraft.util.math.AxisAlignedBB func_180640_a(net.minecraft.block.state.IBlockState state,net.minecraft.world.World world,net.minecraft.util.math.BlockPos position){return null;}
}
