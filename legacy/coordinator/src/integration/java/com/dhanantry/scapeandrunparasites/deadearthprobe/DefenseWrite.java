package com.dhanantry.scapeandrunparasites.deadearthprobe;
import dev.deadearth.legacy.Reflect;
/** Isolated probe-only native package frame; not shipped in the coordinator. */
public final class DefenseWrite {public static boolean replace(Object world,Object pos,Object state){return (Boolean)Reflect.call(world,"setBlockState/func_180501_a",pos,state,3);}}
