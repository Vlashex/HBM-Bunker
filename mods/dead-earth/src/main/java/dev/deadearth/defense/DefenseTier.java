package dev.deadearth.defense;

public enum DefenseTier {
    V1(1000),V2(2000),V3(8000);
    private final int hp;
    DefenseTier(int hp){this.hp=hp;}
    public int defaultHp(){return hp;}
}
