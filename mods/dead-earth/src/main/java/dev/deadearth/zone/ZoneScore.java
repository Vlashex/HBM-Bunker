package dev.deadearth.zone;

/** Saturating stock accounting. Unknown/disabled defenses are not empty defenses. */
public final class ZoneScore {
    public enum Defense {ARMED,EMPTY,DISABLED,UNKNOWN}
    private long emc,unpriced,armed,empty;private int unknown;private boolean overflow;
    private long add(long a,long b) {if(Long.MAX_VALUE-a<b){overflow=true;return Long.MAX_VALUE;}return a+b;}
    public void stock(long unit,long quantity) {
        if(unit<0||quantity<0)throw new IllegalArgumentException("Negative resource value");
        if(unit==0){unpriced=add(unpriced,quantity);return;}
        long value;if(quantity>Long.MAX_VALUE/unit){overflow=true;value=Long.MAX_VALUE;}else value=unit*quantity;
        emc=add(emc,value);
    }
    public void turret(Defense state,int rank) {
        if(rank<0)throw new IllegalArgumentException("Negative turret rank");
        switch(state){case ARMED->armed=add(armed,rank);case EMPTY->empty=add(empty,rank);case UNKNOWN->unknown++;default->{}}
    }
    public long emc(){return emc;}public long unpricedItems(){return unpriced;}
    public long armedRank(){return armed;}public long emptyRank(){return empty;}
    public int unknownTurrets(){return unknown;}public boolean overflow(){return overflow;}
    public double value(double coefficient) {
        if(!Double.isFinite(coefficient)||coefficient<0)throw new IllegalArgumentException("Invalid resource coefficient");
        return emc*coefficient-armed+empty;
    }
}
