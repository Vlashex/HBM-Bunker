package dev.deadearth.legacy.climate;

import java.util.HashSet;
import java.util.Set;

/** One shared budget per server tick, including all reads and changed chunk sections. */
public final class ClimateBudget {
    private int cells, edits;
    private final int sectionLimit;
    private final Set<String> sections = new HashSet<String>();
    public int reads, changes;
    public ClimateBudget(int cells, int edits, int sections) {
        if (cells < 0 || edits < 0 || sections < 0) throw new IllegalArgumentException("Negative climate budget");
        this.cells=cells; this.edits=edits; this.sectionLimit=sections;
    }
    public boolean read() { if (cells==0) return false; cells--; reads++; return true; }
    public boolean canWrite(int dimension,int x,int y,int z) {
        return edits>0 && (sections.contains(key(dimension,x,y,z)) || sections.size()<sectionLimit);
    }
    public void written(int dimension,int x,int y,int z) {
        if (!canWrite(dimension,x,y,z)) throw new IllegalStateException("Climate edit exceeded budget");
        sections.add(key(dimension,x,y,z)); edits--; changes++;
    }
    public boolean exhausted() { return cells==0 || edits==0; }
    public int sections() { return sections.size(); }
    private static String key(int d,int x,int y,int z) { return d+":"+(x>>4)+":"+(y>>4)+":"+(z>>4); }
}
