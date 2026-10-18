package dev.deadearth.evolution;

import java.util.*;

/** Resolve even a restored wave against today's phase and its finite allocation. */
public final class EvolutionWaves {
    private EvolutionWaves() {}
    public static List<String> plan(EvolutionCatalog catalog,int phase,String role,List<String> requested,int budget) {
        if(phase<0||phase>5||budget<0||budget>32)throw new IllegalArgumentException("Invalid wave allocation");
        boolean controlled=catalog.enabled();
        var pool=controlled&&Set.of("scouts","flankers").contains(role)?catalog.wave(phase,role):requested;
        var allowed=pool.stream().filter(id->!id.startsWith("mutationcraft:")||catalog.allowsEntity(phase,id)).toList();
        var result=new ArrayList<String>();int cursor=0;
        while(budget>0&&!allowed.isEmpty()) {
            boolean added=false;
            for(int i=0;i<allowed.size();i++) {
                String id=allowed.get(cursor++%allowed.size());
                int cost=controlled&&id.startsWith("mutationcraft:")?catalog.cost(id):1;
                if(cost>budget)continue;
                result.add(id);budget-=cost;added=true;break;
            }
            if(!added)break;
        }
        return List.copyOf(result);
    }
}
