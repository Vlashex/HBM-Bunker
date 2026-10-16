package dev.deadearth.zone;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Physical block loaders; touching boundaries alone do not merge zones. */
public final class ZoneTopology {
    public record Loader(String dimension,int x,int y,int z) implements Comparable<Loader> {
        public Loader {Objects.requireNonNull(dimension);}
        @Override public int compareTo(Loader b) {
            int n=dimension.compareTo(b.dimension);if(n!=0)return n;
            n=Integer.compare(x,b.x);if(n!=0)return n;
            n=Integer.compare(y,b.y);return n!=0?n:Integer.compare(z,b.z);
        }
    }
    public record Zone(UUID id,String dimension,List<Loader> loaders,Set<Long> chunks) {}
    private ZoneTopology() {}
    public static long chunk(int x,int z) {return (x&0xffffffffL)|((z&0xffffffffL)<<32);}
    public static Set<Long> footprint(Loader loader) {
        var result=new TreeSet<Long>();int x=Math.floorDiv(loader.x,16),z=Math.floorDiv(loader.z,16);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)result.add(chunk(x+dx,z+dz));
        return Collections.unmodifiableSet(result);
    }
    public static List<Zone> build(Collection<Loader> input) {
        var loaders=new ArrayList<>(new TreeSet<>(input));var groups=new ArrayList<List<Loader>>();
        for(var loader:loaders) {
            var merged=new ArrayList<Loader>();merged.add(loader);var area=new HashSet<>(footprint(loader));
            for(var it=groups.iterator();it.hasNext();) {
                var group=it.next();
                if(group.getFirst().dimension.equals(loader.dimension)&&group.stream().anyMatch(l->!Collections.disjoint(area,footprint(l)))) {
                    merged.addAll(group);for(var l:group)area.addAll(footprint(l));it.remove();
                }
            }
            groups.add(merged);
        }
        var zones=new ArrayList<Zone>();
        for(var group:groups) {
            group.sort(Comparator.naturalOrder());var chunks=new TreeSet<Long>();var key=new StringBuilder("dead-earth-zone-v1\n");
            for(var l:group){chunks.addAll(footprint(l));key.append(l.dimension).append('/').append(l.x).append('/').append(l.y).append('/').append(l.z).append('\n');}
            zones.add(new Zone(UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)),group.getFirst().dimension,
                    List.copyOf(group),Collections.unmodifiableSet(chunks)));
        }
        zones.sort(Comparator.comparing(z->z.loaders.getFirst()));return List.copyOf(zones);
    }
}
