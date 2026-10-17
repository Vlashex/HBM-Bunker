package dev.deadearth.defense;

/** Implemented by wall, door and hatch blocks. No per-block ticking or block entity. */
public interface DefenseMaterial {
    DefenseTier defenseTier();
}
