package dev.deadearth.legacy.climate;

import dev.deadearth.legacy.Reflect;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.IBlockAccess;
import net.minecraft.util.math.*;

/** Fixed states avoid metadata overflow: five directions × eight genuine collision layers per material. */
public final class CoatingBlock extends Block {
    public final CoatingEngine.Cell cell;
    public final String key;
    private final AxisAlignedBB shape;
    public CoatingBlock(boolean snow,int face,int layers) {
        super((Material)Reflect.get(Reflect.type("net.minecraft.block.material.Material"),snow?"CRAFTED_SNOW/field_151597_y":"ICE/field_151588_w"));
        cell=CoatingEngine.Cell.coating(snow,face,layers);key=(snow?"season_snow_":"season_rime_")+face+"_"+layers;
        Reflect.call(this,"setRegistryName","dead_earth",key);Reflect.call(this,"setHardness/func_149711_c",snow?.25f:.5f);
        Reflect.call(this,"setUnlocalizedName/func_149663_c","dead_earth."+key);
        Reflect.call(this,"setLightOpacity/func_149713_g",snow?1:2);
        Reflect.set(this,"slipperiness/field_149765_K",snow?.6f:.98f);
        double t=layers/8.0;
        shape=face==CoatingEngine.UP?new AxisAlignedBB(0,0,0,1,t,1):face==CoatingEngine.NORTH?new AxisAlignedBB(0,0,1-t,1,1,1):
            face==CoatingEngine.SOUTH?new AxisAlignedBB(0,0,0,1,1,t):face==CoatingEngine.WEST?new AxisAlignedBB(1-t,0,0,1,1,1):new AxisAlignedBB(0,0,0,t,1,1);
    }
    public boolean func_149662_c(IBlockState s){return false;}
    public boolean func_149686_d(IBlockState s){return cell!=null&&cell.layers==8;}
    public AxisAlignedBB func_180640_a(IBlockState s,IBlockAccess w,BlockPos p){return shape;}
    public AxisAlignedBB func_185496_a(IBlockState s,IBlockAccess w,BlockPos p){return shape;}
    public net.minecraft.util.BlockRenderLayer func_180664_k(){return cell.snow?net.minecraft.util.BlockRenderLayer.SOLID:net.minecraft.util.BlockRenderLayer.TRANSLUCENT;}
    public int func_149745_a(java.util.Random random){return 0;}
}
