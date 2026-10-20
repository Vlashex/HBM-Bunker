package dev.deadearth.legacy.farming;

import java.util.*;

/** Final verification stage only; no Minecraft boot required for the conservation/packing kernel. */
public final class FarmingChecks {
    static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("Farming check "+checks);}
    static final class Stack {String id;int n,limit;Stack(String i,int n,int l){id=i;this.n=n;limit=l;}}
    static final InventoryPacking.Ops<Stack> OPS=new InventoryPacking.Ops<Stack>() {
        public Stack copy(Stack a){return new Stack(a.id,a.n,a.limit);}public int count(Stack a){return a.n;}
        public void count(Stack a,int n){a.n=n;}public int limit(Stack a){return a.limit;}
        public boolean same(Stack a,Stack b){return a.id.equals(b.id);}public Stack empty(){return new Stack("",0,64);}
    };
    public static void main(String[] args) {
        List<Stack> inventory=Arrays.asList(new Stack("crop",63,64),new Stack("",0,64));
        List<Stack> planned=InventoryPacking.all(inventory,Arrays.asList(new Stack("crop",64,64)),OPS);
        check(inventory.get(0).n==63&&inventory.get(1).n==0);check(planned.get(0).n==64&&planned.get(1).n==63);
        List<Stack> full=Arrays.asList(new Stack("crop",64,64));
        check(InventoryPacking.all(full,Arrays.asList(new Stack("seed",1,64)),OPS)==null&&full.get(0).n==64);
        InventoryPacking.Result<Stack> partial=InventoryPacking.insert(Arrays.asList(new Stack("crop",60,64)),new Stack("crop",10,64),OPS);
        check(partial.slots.get(0).n==64&&partial.remainder.n==6);
        List<Stack> nativeDrops=Arrays.asList(new Stack("seed",2,64),new Stack("essence",1,64));
        List<Stack> yield=InventoryPacking.reserveSeed(nativeDrops,new Stack("seed",1,64),OPS);
        check(nativeDrops.get(0).n==2&&yield.get(0).n==1&&yield.get(1).n==1);
        check(InventoryPacking.reserveSeed(Arrays.asList(new Stack("seed",1,64)),new Stack("seed",1,64),OPS).isEmpty());
        List<Stack> small=InventoryPacking.all(Arrays.asList(new Stack("",0,64),new Stack("",0,64)),Arrays.asList(new Stack("limited",20,16)),OPS);
        check(small.get(0).n==16&&small.get(1).n==4);
        List<Stack> retrieval=InventoryPacking.all(Arrays.asList(new Stack("",0,64)),Arrays.asList(new Stack("seed",1,64),new Stack("soil",1,64)),OPS);
        check(retrieval==null); // Both planted inputs are returned together or neither is removed.
        System.out.println("FarmingChecks: "+checks+" checks passed");
    }
}
