package dev.deadearth.legacy.gameplay;

public final class WaterStacksChecks {
  public static final class Tag {
    private final String potion;
    private final boolean effects;
    Tag(String p, boolean e) { potion = p; effects = e; }
    public String getString(String key) { return potion; }
    public boolean hasKey(String key) { return effects; }
  }
  public static final class Stack {
    private final Tag tag;
    Stack(Tag t) { tag = t; }
    public Tag getTagCompound() { return tag; }
  }
  public static void main(String[] args) {
    check(WaterStacks.potionLimit(new Stack(new Tag("minecraft:water", false))) == 64);
    check(WaterStacks.potionLimit(new Stack(new Tag("water", false))) == 64);
    check(WaterStacks.potionLimit(new Stack(new Tag("minecraft:water", true))) == 1);
    check(WaterStacks.potionLimit(new Stack(new Tag("minecraft:poison", false))) == 1);
    check(WaterStacks.potionLimit(new Stack(new Tag("minecraft:healing", false))) == 1);
    check(WaterStacks.potionLimit(new Stack(new Tag("minecraft:awkward", false))) == 1);
    check(WaterStacks.potionLimit(new Stack(null)) == 1);
    check(WaterStacks.potionLimit(null) == 1);
    System.out.println("Water stack checks: 8 passed");
  }
  private static void check(boolean ok) { if (!ok) throw new AssertionError("Water stack limit"); }
}
