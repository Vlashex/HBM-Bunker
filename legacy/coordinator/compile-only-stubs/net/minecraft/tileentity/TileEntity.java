package net.minecraft.tileentity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
public class TileEntity {
 public void func_145843_s(){} public void onChunkUnload(){}
 public NBTTagCompound func_189515_b(NBTTagCompound n){return n;}
 public void func_145839_a(NBTTagCompound n){}
 public NBTTagCompound func_189517_E_(){return new NBTTagCompound();}
 public boolean hasCapability(Capability<?> c,EnumFacing f){return false;}
 public <T>T getCapability(Capability<T> c,EnumFacing f){return null;}
}
