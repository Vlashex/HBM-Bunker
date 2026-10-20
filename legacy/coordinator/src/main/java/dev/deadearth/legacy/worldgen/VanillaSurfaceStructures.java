package dev.deadearth.legacy.worldgen;

import dev.deadearth.legacy.Reflect;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.structure.*;

/** Exact vanilla generators only. Forge CUSTOM/HBM and all other dimensions are untouched. */
final class VanillaSurfaceStructures {
    private VanillaSurfaceStructures(){}
    static Object replacement(Object original,String type){
        String name=original.getClass().getName();
        if(type.equals("VILLAGE")&&name.equals("net.minecraft.world.gen.structure.MapGenVillage"))return new Village();
        if(type.equals("SCATTERED_FEATURE")&&name.equals("net.minecraft.world.gen.structure.MapGenScatteredFeature"))return new Temple();
        if(type.equals("WOODLAND_MANSION")&&name.equals("net.minecraft.world.gen.structure.WoodlandMansion"))
            return new Mansion((ChunkGeneratorOverworld)Reflect.get(original,"chunkGenerator/field_191075_h"));
        return original;
    }
    private static boolean overworld(Object gen){
        Object world=Reflect.get(gen,"world/field_75039_c");return world!=null&&Reflect.dimension(world)==0;
    }
    static final class Village extends MapGenVillage {
        protected boolean func_75047_a(int x,int z){return !overworld(this)&&super.func_75047_a(x,z);}
    }
    static final class Temple extends MapGenScatteredFeature {
        protected boolean func_75047_a(int x,int z){return !overworld(this)&&super.func_75047_a(x,z);}
    }
    static final class Mansion extends WoodlandMansion {
        Mansion(ChunkGeneratorOverworld generator){super(generator);}
        protected boolean func_75047_a(int x,int z){return !overworld(this)&&super.func_75047_a(x,z);}
    }
}
