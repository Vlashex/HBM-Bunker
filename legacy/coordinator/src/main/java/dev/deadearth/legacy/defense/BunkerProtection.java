package dev.deadearth.legacy.defense;

import java.util.regex.*;

/** Campaign-time deadlines: extend from now without shortening an existing grace period. */
public final class BunkerProtection {
  private static final long MAX_DURATION = 30L * 24 * 60 * 60 * 20;
  private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,5})([smhd])");

  private BunkerProtection() {}

  public static long durationTicks(String text) {
    Matcher match = DURATION.matcher(text);
    if (!match.matches()) throw new IllegalArgumentException("Use 1h, 30m, 60s or 1d");
    long unit = match.group(2).equals("s") ? 20L
        : match.group(2).equals("m") ? 1200L
        : match.group(2).equals("h") ? 72000L : 1728000L;
    long ticks = Long.parseLong(match.group(1)) * unit;
    if (ticks > MAX_DURATION) throw new IllegalArgumentException("Maximum duration is 30 days");
    return ticks;
  }

  public static long deadline(long configured, long saved) {
    if (configured < 0 || saved < 0) throw new IllegalArgumentException("Negative deadline");
    return Math.max(configured, saved);
  }

  public static long extendFromNow(long now, long configured, long saved, long duration) {
    if (now < 0 || duration <= 0 || duration > MAX_DURATION)
      throw new IllegalArgumentException("Invalid protection extension");
    return Math.max(deadline(configured, saved), Math.addExact(now, duration));
  }

  public static long remaining(long now, long configured, long saved) {
    if (now < 0) throw new IllegalArgumentException("Negative campaign clock");
    return Math.max(0L, deadline(configured, saved) - now);
  }

  public static String describe(long ticks) {
    long seconds = ticks / 20 + (ticks % 20 == 0 ? 0 : 1);
    return (seconds / 3600) + " ч " + ((seconds / 60) % 60) + " мин " + (seconds % 60) + " с";
  }
}
