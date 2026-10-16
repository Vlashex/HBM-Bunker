package dev.deadearth.difficulty;
import static dev.deadearth.config.DeadEarthConfig.*;
public final class AdaptivePolicy {
 public record Window(double kills,double damage,double deaths,double healthFraction,boolean active) {
  public Window {if(!Double.isFinite(kills)||!Double.isFinite(damage)||!Double.isFinite(deaths)||!Double.isFinite(healthFraction)
   ||kills<0||damage<0||deaths<0||healthFraction<0||healthFraction>1) throw new IllegalArgumentException("Invalid performance window");}
 }
 private AdaptivePolicy() {}
 public static double next(double current,Window w) {
  validate(current);if(!w.active()) return current;
  double target=Math.clamp(1+Math.min(d(ADAPTIVE_ABOVE),w.kills()*d(KILL_WEIGHT))-w.damage()*d(DAMAGE_WEIGHT)-w.deaths()*d(DEATH_WEIGHT)-(1-w.healthFraction())*d(LOW_HEALTH_WEIGHT),minPressure(),maxPressure());
  return Math.clamp(current+Math.clamp(target-current,-d(ADAPTIVE_STEP),d(ADAPTIVE_STEP)),minPressure(),maxPressure());
 }
 public static void validate(double value) {if(!Double.isFinite(value)||value<minPressure()||value>maxPressure()) throw new IllegalArgumentException("Invalid adaptive pressure");}
}
