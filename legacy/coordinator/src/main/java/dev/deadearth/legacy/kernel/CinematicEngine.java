package dev.deadearth.legacy.kernel;

import static dev.deadearth.legacy.kernel.CinematicCatalog.*;
import static dev.deadearth.legacy.kernel.KernelSupport.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.*;

/** Pure finite-state queue. Every physical effect is checkpointed BEFORE the world adapter runs. */
public final class CinematicEngine {
  private static final Gson JSON = new Gson();

  public enum State {
    QUEUED,
    WAITING,
    APPLYING,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    UNCERTAIN
  }

  public static final class Zone extends KernelValue {
    public final String id;
    public final double value;
    public final long armed;
    public final long empty;
    public final long age;
    public final boolean complete;
    public final boolean overflow;
    public final int players;
    public final double playerScore;
    public final double losses;

    public Zone(
        String id,
        double value,
        long armed,
        long empty,
        long age,
        boolean complete,
        boolean overflow,
        int players,
        double playerScore,
        double losses) {
      this.id = id;
      this.value = value;
      this.armed = armed;
      this.empty = empty;
      this.age = age;
      this.complete = complete;
      this.overflow = overflow;
      this.players = players;
      this.playerScore = playerScore;
      this.losses = losses;
    }

    public String id() {
      return id;
    }

    public double value() {
      return value;
    }

    public long armed() {
      return armed;
    }

    public long empty() {
      return empty;
    }

    public long age() {
      return age;
    }

    public boolean complete() {
      return complete;
    }

    public boolean overflow() {
      return overflow;
    }

    public int players() {
      return players;
    }

    public double playerScore() {
      return playerScore;
    }

    public double losses() {
      return losses;
    }

    @Override
    protected Object[] values() {
      return new Object[] {
        id, value, armed, empty, age, complete, overflow, players, playerScore, losses
      };
    }
  }

  public static final class Context extends KernelValue {
    public final long now;
    public final int online;
    public final String season;
    public final double pressure;
    public final List<Zone> zones;
    public final int parasitePhase;

    public Context(
        long now, int online, String season, double pressure, List<Zone> zones, int parasitePhase) {
      this.now = now;
      this.online = online;
      this.season = season;
      this.pressure = pressure;
      this.zones = immutableList(zones);
      this.parasitePhase = parasitePhase;
      if (parasitePhase < 0 || parasitePhase > 10) throw bad("parasite phase");
      if (now < 0 || online < 0 || !Double.isFinite(pressure)) throw bad("context");
    }

    public long now() {
      return now;
    }

    public int online() {
      return online;
    }

    public String season() {
      return season;
    }

    public double pressure() {
      return pressure;
    }

    public List<Zone> zones() {
      return zones;
    }

    public int parasitePhase() {
      return parasitePhase;
    }

    @Override
    protected Object[] values() {
      return new Object[] {now, online, season, pressure, zones, parasitePhase};
    }

    public Context(long now, int online, String season, double pressure, List<Zone> zones) {
      this(now, online, season, pressure, zones, 10);
    }
  }

  public static final class Option extends KernelValue {
    public final String eventId;
    public final String zoneId;
    public final String title;
    public final String tactic;
    public final String faction;
    public final double weight;
    public final int budget;

    public Option(
        String eventId,
        String zoneId,
        String title,
        String tactic,
        String faction,
        double weight,
        int budget) {
      this.eventId = eventId;
      this.zoneId = zoneId;
      this.title = title;
      this.tactic = tactic;
      this.faction = faction;
      this.weight = weight;
      this.budget = budget;
    }

    public String eventId() {
      return eventId;
    }

    public String zoneId() {
      return zoneId;
    }

    public String title() {
      return title;
    }

    public String tactic() {
      return tactic;
    }

    public String faction() {
      return faction;
    }

    public double weight() {
      return weight;
    }

    public int budget() {
      return budget;
    }

    @Override
    protected Object[] values() {
      return new Object[] {eventId, zoneId, title, tactic, faction, weight, budget};
    }
  }

  public static final class Goal extends KernelValue {
    public final UUID id;
    public final UUID epoch;
    public final String catalogRevision;
    public final long created;
    public final long expires;
    public final List<Option> options;

    public Goal(
        UUID id,
        UUID epoch,
        String catalogRevision,
        long created,
        long expires,
        List<Option> options) {
      this.id = id;
      this.epoch = epoch;
      this.catalogRevision = catalogRevision;
      this.created = created;
      this.expires = expires;
      this.options = immutableList(options);
    }

    public UUID id() {
      return id;
    }

    public UUID epoch() {
      return epoch;
    }

    public String catalogRevision() {
      return catalogRevision;
    }

    public long created() {
      return created;
    }

    public long expires() {
      return expires;
    }

    public List<Option> options() {
      return options;
    }

    @Override
    protected Object[] values() {
      return new Object[] {id, epoch, catalogRevision, created, expires, options};
    }
  }

  public static final class History extends KernelValue {
    public final UUID requestId;
    public final UUID operationId;
    public final String eventId;
    public final String zoneId;
    public final long tick;
    public final String outcome;
    public final int spent;

    public History(
        UUID requestId,
        UUID operationId,
        String eventId,
        String zoneId,
        long tick,
        String outcome,
        int spent) {
      this.requestId = requestId;
      this.operationId = operationId;
      this.eventId = eventId;
      this.zoneId = zoneId;
      this.tick = tick;
      this.outcome = outcome;
      this.spent = spent;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID operationId() {
      return operationId;
    }

    public String eventId() {
      return eventId;
    }

    public String zoneId() {
      return zoneId;
    }

    public long tick() {
      return tick;
    }

    public String outcome() {
      return outcome;
    }

    public int spent() {
      return spent;
    }

    @Override
    protected Object[] values() {
      return new Object[] {requestId, operationId, eventId, zoneId, tick, outcome, spent};
    }
  }

  public static final class Operation extends KernelValue {
    public final UUID id;
    public final UUID requestId;
    public final String frozenCatalog;
    public final String eventId;
    public final String zoneId;
    public final State state;
    public final String node;
    public final int cursor;
    public final long created;
    public final long expires;
    public final long entered;
    public final int budget;
    public final int spent;
    public final String outcome;
    public final Set<String> latched;

    public Operation(
        UUID id,
        UUID requestId,
        String frozenCatalog,
        String eventId,
        String zoneId,
        State state,
        String node,
        int cursor,
        long created,
        long expires,
        long entered,
        int budget,
        int spent,
        String outcome,
        Set<String> latched) {
      this.id = id;
      this.requestId = requestId;
      this.frozenCatalog = frozenCatalog;
      this.eventId = eventId;
      this.zoneId = zoneId;
      this.state = state;
      this.node = node;
      this.cursor = cursor;
      this.created = created;
      this.expires = expires;
      this.entered = entered;
      this.budget = budget;
      this.spent = spent;
      this.outcome = outcome;
      this.latched = immutableSet(latched);
      if (this.latched.size()
              > CinematicCatalog.TRIGGERS.size() * (CinematicCatalog.ROLES.size() + 1)
          || this.latched.stream().anyMatch(v -> !CinematicCatalog.validTrigger(v)))
        throw bad("latched signals");
    }

    public UUID id() {
      return id;
    }

    public UUID requestId() {
      return requestId;
    }

    public String frozenCatalog() {
      return frozenCatalog;
    }

    public String eventId() {
      return eventId;
    }

    public String zoneId() {
      return zoneId;
    }

    public State state() {
      return state;
    }

    public String node() {
      return node;
    }

    public int cursor() {
      return cursor;
    }

    public long created() {
      return created;
    }

    public long expires() {
      return expires;
    }

    public long entered() {
      return entered;
    }

    public int budget() {
      return budget;
    }

    public int spent() {
      return spent;
    }

    public String outcome() {
      return outcome;
    }

    public Set<String> latched() {
      return latched;
    }

    @Override
    protected Object[] values() {
      return new Object[] {
        id,
        requestId,
        frozenCatalog,
        eventId,
        zoneId,
        state,
        node,
        cursor,
        created,
        expires,
        entered,
        budget,
        spent,
        outcome,
        latched
      };
    }
  }

  public static final class Snapshot extends KernelValue {
    public final List<Operation> operations;
    public final List<History> history;
    public final long nextDecision;
    public final long recoveryUntil;
    public final long heavyUntil;
    public final int pressuresSinceRest;

    public Snapshot(
        List<Operation> operations,
        List<History> history,
        long nextDecision,
        long recoveryUntil,
        long heavyUntil,
        int pressuresSinceRest) {
      this.operations = immutableList(operations);
      this.history = immutableList(history);
      this.nextDecision = nextDecision;
      this.recoveryUntil = recoveryUntil;
      this.heavyUntil = heavyUntil;
      this.pressuresSinceRest = pressuresSinceRest;
    }

    public List<Operation> operations() {
      return operations;
    }

    public List<History> history() {
      return history;
    }

    public long nextDecision() {
      return nextDecision;
    }

    public long recoveryUntil() {
      return recoveryUntil;
    }

    public long heavyUntil() {
      return heavyUntil;
    }

    public int pressuresSinceRest() {
      return pressuresSinceRest;
    }

    @Override
    protected Object[] values() {
      return new Object[] {
        operations, history, nextDecision, recoveryUntil, heavyUntil, pressuresSinceRest
      };
    }
  }

  public static final class Verdict extends KernelValue {
    public final UUID requestId;
    public final UUID goalId;
    public final boolean accepted;
    public final String code;
    public final UUID operationId;

    public Verdict(UUID requestId, UUID goalId, boolean accepted, String code, UUID operationId) {
      this.requestId = requestId;
      this.goalId = goalId;
      this.accepted = accepted;
      this.code = code;
      this.operationId = operationId;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID goalId() {
      return goalId;
    }

    public boolean accepted() {
      return accepted;
    }

    public String code() {
      return code;
    }

    public UUID operationId() {
      return operationId;
    }

    @Override
    protected Object[] values() {
      return new Object[] {requestId, goalId, accepted, code, operationId};
    }
  }

  public static final class ManualPreview extends KernelValue {
    public final boolean accepted;
    public final String code;
    public final Option option;

    public ManualPreview(boolean accepted, String code, Option option) {
      this.accepted = accepted;
      this.code = code;
      this.option = option;
    }

    public boolean accepted() {
      return accepted;
    }

    public String code() {
      return code;
    }

    public Option option() {
      return option;
    }

    @Override
    protected Object[] values() {
      return new Object[] {accepted, code, option};
    }
  }

  public interface World {
    boolean ready(Operation operation);

    Set<String> signals(Operation operation);

    int livingOwned();

    /** Count is the remaining finite wave allocation. Never replace casualties. */
    void emit(Operation operation, Action action, int count, CinematicCatalog frozen);

    void checkpoint();
  }

  private final LinkedHashMap<UUID, Operation> operations = new LinkedHashMap<>();
  private final ArrayList<History> history = new ArrayList<>();
  private final HashMap<UUID, CinematicCatalog> frozen = new HashMap<>();
  private long nextDecision, recoveryUntil, heavyUntil;
  private int pressuresSinceRest;
  private Goal goal;

  public List<Operation> operations() {
    return immutableList(operations.values());
  }

  public List<History> history() {
    return immutableList(history);
  }

  public Snapshot snapshot() {
    return new Snapshot(
        operations(), history(), nextDecision, recoveryUntil, heavyUntil, pressuresSinceRest);
  }

  public CinematicCatalog frozen(Operation op) {
    return frozen.get(op.id);
  }

  public static CinematicEngine restore(Snapshot s) {
    if (s == null
        || s.operations == null
        || s.history == null
        || s.operations.size() > 19
        || s.history.size() > 512
        || s.nextDecision < 0
        || s.recoveryUntil < 0
        || s.heavyUntil < 0
        || s.pressuresSinceRest < 0
        || s.pressuresSinceRest > 10) throw bad("snapshot");
    CinematicEngine engine = new CinematicEngine();
    engine.nextDecision = s.nextDecision;
    engine.recoveryUntil = s.recoveryUntil;
    engine.heavyUntil = s.heavyUntil;
    engine.pressuresSinceRest = s.pressuresSinceRest;
    HashSet<UUID> requests = new HashSet<UUID>();
    HashSet<UUID> operationIds = new HashSet<UUID>();
    ArrayList<History> interrupted = new ArrayList<History>();
    for (Operation op : s.operations) {
      if (op == null
          || op.id == null
          || op.requestId == null
          || op.state == null
          || op.outcome == null
          || !op.outcome.isEmpty()
          || op.created < 0
          || op.expires <= op.created
          || op.entered < op.created
          || op.entered > op.expires
          || op.budget < 1
          || op.budget > 32
          || op.spent < 0
          || op.spent > op.budget
          || !uuid(op.zoneId)
          || op.latched == null
          || op.latched.size()
              > CinematicCatalog.TRIGGERS.size() * (CinematicCatalog.ROLES.size() + 1)
          || op.latched.stream().anyMatch(v -> !CinematicCatalog.validTrigger(v))
          || !requests.add(op.requestId)
          || !operationIds.add(op.id)) throw bad("operation");
      CinematicCatalog catalog = CinematicCatalog.parse(op.frozenCatalog);
      Event event = catalog.events().get(op.eventId);
      if (event == null
          || catalog.events().size() != 1
          || !event.nodes().containsKey(op.node)
          || op.cursor < 0
          || op.cursor > event.nodes().get(op.node).actions().size()
          || op.expires - op.created != event.lifetimeTicks()
          || !set(State.QUEUED, State.WAITING, State.APPLYING).contains(op.state))
        throw bad("frozen operation");
      if (op.state == State.APPLYING)
        interrupted.add(
            new History(
                op.requestId, op.id, op.eventId, op.zoneId, op.entered, "uncertain", op.spent));
      else {
        // Gson 2.8 bypasses normal constructors. Normalize its decoded
        // mutable collections before exposing a restored operation.
        Operation immutable = withSignals(op, op.latched);
        engine.frozen.put(op.id, catalog);
        engine.operations.put(op.id, immutable);
      }
    }
    for (History h : s.history) {
      if (h == null
          || h.requestId == null
          || h.operationId == null
          || h.eventId == null
          || !h.eventId.matches("[a-z][a-z0-9_]{0,63}")
          || !uuid(h.zoneId)
          || h.tick < 0
          || h.spent < 0
          || h.spent > 32
          || !validOutcome(h.outcome)
          || !requests.add(h.requestId)
          || !operationIds.add(h.operationId)) throw bad("history");
      engine.history.add(h);
    }
    // Newly interrupted operations are the most recent receipts. Preserve
    // them when the bounded saved history was already at capacity.
    engine.history.addAll(interrupted);
    engine.trim(512);
    return engine;
  }

  private static boolean uuid(String s) {
    try {
      return s != null && UUID.fromString(s).toString().equals(s);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean validOutcome(String s) {
    return OUTCOMES.contains(s) || set("uncertain", "expired", "cancelled").contains(s);
  }

  private static IllegalArgumentException bad(String s) {
    return new IllegalArgumentException("Director queue: " + s);
  }

  public Goal brief(UUID epoch, CinematicCatalog catalog, Context c, boolean enabled) {
    if (!enabled || c.online == 0 || c.now < recoveryUntil) {
      goal = null;
      return null;
    }
    if (goal != null
        && goal.epoch.equals(epoch)
        && goal.catalogRevision.equals(catalog.revision())
        && c.now < goal.expires) return goal;
    goal = null;
    if (c.now < nextDecision
        || operations.size() >= catalog.rules().maxQueued() + catalog.rules().maxActive())
      return null;
    List<Option> options = options(catalog, c);
    nextDecision =
        Math.addExact(
            c.now,
            options.isEmpty()
                ? catalog.rules().count("retryTicks")
                : catalog.rules().decisionTicks());
    if (options.isEmpty()) return null;
    goal =
        new Goal(
            UUID.randomUUID(),
            epoch,
            catalog.revision(),
            c.now,
            Math.addExact(c.now, catalog.rules().goalTicks()),
            options);
    return goal;
  }

  public List<Option> options(CinematicCatalog catalog, Context c) {
    ArrayList<Option> out = new ArrayList<Option>(eligibleOptions(catalog, c));
    Rules r = catalog.rules();
    out.sort(
        Comparator.comparingDouble((Option o) -> lottery(o, c.now / r.decisionTicks()))
            .thenComparing(Option::eventId)
            .thenComparing(Option::zoneId));
    // Diversity across tactics and zones, bounded model context.
    ArrayList<Option> shortlist = new ArrayList<Option>();
    HashMap<String, Integer> counts = new HashMap<String, Integer>();
    for (Option o : out)
      if (counts.getOrDefault(o.zoneId, 0) < r.count("optionsPerZone")) {
        shortlist.add(o);
        counts.merge(o.zoneId, 1, Integer::sum);
        if (shortlist.size() == r.count("modelOptionLimit")) break;
      }
    return immutableList(shortlist);
  }

  private List<Option> eligibleOptions(CinematicCatalog catalog, Context c) {
    ArrayList<Option> out = new ArrayList<Option>();
    Rules r = catalog.rules();
    for (Zone z : c.zones) {
      // Incomplete scans stay UNKNOWN, including otherwise tempting high scores.
      if (!z.complete
          || z.overflow
          || z.age < 0
          || z.age > r.maximumZoneAgeTicks()
          || z.players < 1
          || !uuid(z.id)
          || !Double.isFinite(z.value)
          || !Double.isFinite(z.playerScore)
          || !Double.isFinite(z.losses)) continue;
      if (operations.values().stream().anyMatch(op -> op.zoneId.equals(z.id))) continue;
      if (history.stream()
          .anyMatch(h -> h.zoneId.equals(z.id) && c.now - h.tick < r.zoneCooldownTicks())) continue;
      for (Event e : catalog.events().values()) {
        if (!phaseEligible(catalog, e, c.parasitePhase)) continue;
        if (!e.seasons().contains(c.season)
            || z.value < e.minScore()
            || z.players < e.minPlayers()
            || e.heavy() && c.now < heavyUntil) continue;
        if (history.stream()
            .anyMatch(h -> h.eventId.equals(e.id()) && c.now - h.tick < e.cooldownTicks()))
          continue;
        Map<String, Double> factors = new HashMap<String, Double>();
        factors.put(
            "value", Math.log1p(Math.max(0, z.value)) / r.coefficient("valueNormalization"));
        factors.put("defense", Math.min(z.armed, 32) / r.coefficient("defenseNormalization"));
        factors.put("empty_defense", Math.min(z.empty, 32) / r.coefficient("defenseNormalization"));
        factors.put("pressure", clamp(c.pressure - 1, -1, 2));
        factors.put("players", Math.min(z.players, 6) / 6d);
        factors.put("losses", clamp(z.losses, 0, r.coefficient("lossMaximum")));
        double affinity =
            e.affinity().entrySet().stream()
                .mapToDouble(a -> a.getValue() * factors.get(a.getKey()))
                .sum();
        double weight =
            e.weight()
                * Math.exp(
                    clamp(
                        affinity, -r.coefficient("affinityLimit"), r.coefficient("affinityLimit")));
        // Losses lower pressure and the allotted finite head count; defense also subtracts.
        int budget =
            (int)
                Math.floor(
                    r.baseBudget()
                        + r.scoreWeight() * Math.log1p(Math.max(0, z.value))
                        + r.playerWeight()
                            * clamp(z.playerScore, 0, r.coefficient("playerScoreMaximum"))
                        + r.pressureWeight() * (c.pressure - 1)
                        - r.lossWeight() * Math.max(0, z.losses)
                        - Math.min(z.armed, 16) * r.coefficient("defenseBudgetWeight"));
        out.add(
            new Option(
                e.id(),
                z.id,
                e.title(),
                e.tactic(),
                e.faction(),
                weight,
                clamp(budget, 1, r.maxWave())));
      }
    }
    return immutableList(out);
  }

  private static boolean phaseEligible(CinematicCatalog catalog, Event event, int phase) {
    for (Node node : event.nodes().values())
      for (Action action : node.actions())
        if (action.type().equals("wave")
            && catalog.waves().get(action.key()).minimumPhase() > phase) return false;
    return true;
  }

  /**
   * Read-only exact selection; the operator receives the same eligibility and finite allocation as
   * the model.
   */
  public ManualPreview previewManual(
      CinematicCatalog catalog, Context context, boolean enabled, String eventId, String zoneId) {
    if (!enabled) return new ManualPreview(false, "disabled", null);
    if (context.online == 0) return new ManualPreview(false, "no_players", null);
    if (context.now < recoveryUntil) return new ManualPreview(false, "recovery", null);
    if (queueFull(catalog)) return new ManualPreview(false, "queue_full", null);
    Optional<Option> option =
        eligibleOptions(catalog, context).stream()
            .filter(o -> o.eventId.equals(eventId) && o.zoneId.equals(zoneId))
            .findFirst();
    return option
        .map(o -> new ManualPreview(true, "ready", o))
        .orElseGet(() -> new ManualPreview(false, "ineligible", null));
  }

  public Verdict admitManual(
      UUID request,
      String eventId,
      String zoneId,
      CinematicCatalog catalog,
      Context context,
      boolean enabled) {
    Verdict replay = replay(request, null, eventId, zoneId);
    if (replay != null) return replay;
    ManualPreview preview = previewManual(catalog, context, enabled, eventId, zoneId);
    if (!preview.accepted) return new Verdict(request, null, false, preview.code, null);
    return enqueue(request, null, eventId, zoneId, catalog, context, preview.option.budget);
  }

  private boolean queueFull(CinematicCatalog catalog) {
    return operations.size() >= catalog.rules().maxQueued() + catalog.rules().maxActive()
        || operations.values().stream().filter(o -> o.state == State.QUEUED).count()
            >= catalog.rules().maxQueued();
  }

  private Verdict replay(UUID request, UUID goalId, String eventId, String zoneId) {
    for (Operation op : operations.values())
      if (op.requestId.equals(request))
        return new Verdict(
            request,
            goalId,
            op.eventId.equals(eventId) && op.zoneId.equals(zoneId),
            op.eventId.equals(eventId) && op.zoneId.equals(zoneId)
                ? "already_queued"
                : "conflicting_request",
            op.id);
    for (History h : history)
      if (h.requestId.equals(request))
        return new Verdict(
            request,
            goalId,
            h.eventId.equals(eventId) && h.zoneId.equals(zoneId),
            h.eventId.equals(eventId) && h.zoneId.equals(zoneId)
                ? "already_recorded"
                : "conflicting_request",
            h.operationId);
    return null;
  }

  private static double lottery(Option o, long cycle) {
    long seed =
        UUID.nameUUIDFromBytes(
                (o.eventId + "/" + o.zoneId + "/" + cycle)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .getMostSignificantBits();
    double unit = ((seed >>> 11) + .5) * 0x1.0p-53;
    return -Math.log(unit) / o.weight;
  }

  public Verdict admit(
      UUID request,
      UUID goalId,
      String eventId,
      String zoneId,
      UUID epoch,
      CinematicCatalog catalog,
      Context context,
      boolean enabled) {
    Verdict replay = replay(request, goalId, eventId, zoneId);
    if (replay != null) return replay;
    if (!enabled
        || context.online == 0
        || goal == null
        || !goal.id.equals(goalId)
        || !goal.epoch.equals(epoch)
        || context.now >= goal.expires
        || !goal.catalogRevision.equals(catalog.revision()))
      return new Verdict(request, goalId, false, "stale_goal", null);
    Optional<Option> selected =
        goal.options.stream()
            .filter(o -> o.eventId.equals(eventId) && o.zoneId.equals(zoneId))
            .findFirst();
    Optional<Option> current =
        options(catalog, context).stream()
            .filter(o -> o.eventId.equals(eventId) && o.zoneId.equals(zoneId))
            .findFirst();
    if (!selected.isPresent() || !current.isPresent())
      return new Verdict(request, goalId, false, "ineligible", null);
    if (queueFull(catalog)) return new Verdict(request, goalId, false, "queue_full", null);
    return enqueue(request, goalId, eventId, zoneId, catalog, context, current.get().budget);
  }

  private Verdict enqueue(
      UUID request,
      UUID goalId,
      String eventId,
      String zoneId,
      CinematicCatalog catalog,
      Context context,
      int budget) {
    Event event = catalog.events().get(eventId);
    String source = freeze(catalog, eventId);
    CinematicCatalog copy = CinematicCatalog.parse(source);
    UUID id = UUID.randomUUID();
    Operation op =
        new Operation(
            id,
            request,
            source,
            eventId,
            zoneId,
            State.QUEUED,
            event.start(),
            0,
            context.now,
            Math.addExact(context.now, event.lifetimeTicks()),
            context.now,
            budget,
            0,
            "",
            set());
    operations.put(id, op);
    frozen.put(id, copy);
    goal = null;
    if (event.heavy())
      heavyUntil = Math.addExact(context.now, catalog.rules().heavyCooldownTicks());
    return new Verdict(request, goalId, true, "queued", id);
  }

  public static String freeze(CinematicCatalog catalog, String eventId) {
    JsonObject root = JSON.toJsonTree(catalog).getAsJsonObject();
    root.remove("revision");
    root.addProperty("schema", 1);
    Event event = catalog.events().get(eventId);
    com.google.gson.JsonArray list = new com.google.gson.JsonArray();
    // Convert internal maps to the public list-based node/event schema.
    JsonObject e = JSON.toJsonTree(event).getAsJsonObject();
    com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
    event.nodes().values().stream()
        .sorted(Comparator.comparing(Node::id))
        .forEach(n -> nodes.add(JSON.toJsonTree(n)));
    e.add("nodes", nodes);
    list.add(e);
    root.add("events", list);
    JsonObject sounds = new JsonObject();
    JsonObject traces = new JsonObject();
    JsonObject waves = new JsonObject();
    for (Node n : event.nodes().values())
      for (Action a : n.actions())
        switch (a.type()) {
          case "sound":
            sounds.add(a.key(), JSON.toJsonTree(catalog.sounds().get(a.key())));
            break;
          case "trace":
            traces.add(a.key(), JSON.toJsonTree(catalog.traces().get(a.key())));
            break;
          case "wave":
            waves.add(a.key(), JSON.toJsonTree(catalog.waves().get(a.key())));
            break;
        }
    // Empty libraries are legal in a frozen single-event document via a minimal unused default.
    if (sounds.size() == 0) {
      String k = catalog.sounds().keySet().stream().sorted().findFirst().get();
      sounds.add(k, JSON.toJsonTree(catalog.sounds().get(k)));
    }
    if (traces.size() == 0) {
      String k = catalog.traces().keySet().stream().sorted().findFirst().get();
      traces.add(k, JSON.toJsonTree(catalog.traces().get(k)));
    }
    if (waves.size() == 0) {
      String k = catalog.waves().keySet().stream().sorted().findFirst().get();
      waves.add(k, JSON.toJsonTree(catalog.waves().get(k)));
    }
    root.add("sounds", sounds);
    root.add("traces", traces);
    root.add("waves", waves);
    return JSON.toJson(root);
  }

  public void tick(CinematicCatalog catalog, Context c, World world) {
    if (c.online == 0) return;
    int effects = 0;
    for (Operation original : immutableList(operations.values())) {
      Operation op = operations.get(original.id);
      CinematicCatalog frozenCatalog = frozen.get(op.id);
      Event event = frozenCatalog.events().get(op.eventId);
      if (op.state == State.APPLYING) {
        finish(op, "uncertain", c.now, catalog.rules(), world);
        continue;
      }
      if (c.now >= op.expires) {
        finish(op, "expired", c.now, catalog.rules(), world);
        continue;
      }
      if (!world.ready(op)) continue; // Loaded-only, bounded pause shares the same lifetime.
      if (op.state == State.QUEUED) {
        long active = operations.values().stream().filter(x -> x.state != State.QUEUED).count();
        if (active >= catalog.rules().maxActive() || c.now < recoveryUntil) continue;
        op = copy(op, State.WAITING, op.node, 0, c.now, op.spent);
        operations.put(op.id, op);
        world.checkpoint();
      }
      Set<String> observed = world.signals(op);
      HashSet<String> remembered = new HashSet<String>(op.latched);
      for (String value : observed)
        if (CinematicCatalog.validTrigger(value)
            && !set("near", "far", "clear").contains(value.split(":")[0])) remembered.add(value);
      if (!remembered.equals(op.latched)) {
        op = withSignals(op, remembered);
        operations.put(op.id, op);
        world.checkpoint();
      }
      Node n = event.nodes().get(op.node);
      if (op.cursor < n.actions().size()) {
        if (effects >= catalog.rules().maxEffectsPerTick()) continue;
        Action a = n.actions().get(op.cursor);
        if (a.type().equals("finish")) {
          finish(op, a.key(), c.now, catalog.rules(), world);
          continue;
        }
        int count =
            a.type().equals("wave")
                ? Math.min(
                    op.budget - op.spent,
                    Math.min(
                        (int) Math.ceil(op.budget * a.portion()),
                        Math.max(0, catalog.rules().maxEntities() - world.livingOwned())))
                : 0;
        // Persist APPLYING first. A crash after this point becomes UNCERTAIN, never an automatic
        // replay.
        Operation pending = copy(op, State.APPLYING, op.node, op.cursor, c.now, op.spent + count);
        operations.put(op.id, pending);
        world.checkpoint();
        try {
          world.emit(pending, a, count, frozenCatalog);
          effects++;
          operations.put(
              op.id,
              copy(
                  op,
                  State.WAITING,
                  op.node,
                  op.cursor + 1,
                  op.cursor + 1 == n.actions().size() ? c.now : op.entered,
                  op.spent + count));
          world.checkpoint();
        } catch (RuntimeException failed) {
          finish(pending, "uncertain", c.now, catalog.rules(), world);
        }
        continue;
      }
      HashSet<String> signals = new HashSet<String>(observed);
      signals.addAll(op.latched);
      String next = null;
      for (Branch branch : n.branches())
        if (signals.contains(branch.when())) {
          next = branch.next();
          break;
        }
      if (next == null && c.now - op.entered >= n.waitTicks()) next = n.timeout();
      if (next != null) {
        operations.put(
            op.id, withSignals(copy(op, State.WAITING, next, 0, c.now, op.spent), set()));
        world.checkpoint();
      }
    }
  }

  public boolean cancel(UUID id, long now, Rules rules, World world) {
    Operation op = operations.get(id);
    if (op == null) return false;
    finish(op, "cancelled", now, rules, world);
    return true;
  }

  private void finish(Operation op, String outcome, long now, Rules rules, World world) {
    operations.remove(op.id);
    frozen.remove(op.id);
    history.add(new History(op.requestId, op.id, op.eventId, op.zoneId, now, outcome, op.spent));
    pressuresSinceRest++;
    if (pressuresSinceRest >= rules.count("restAfter")
        || outcome.equals("repelled")
        || outcome.equals("contained")) {
      recoveryUntil = Math.max(recoveryUntil, Math.addExact(now, rules.recoveryTicks()));
      pressuresSinceRest = 0;
    }
    trim(rules.historyLimit());
    world.checkpoint();
  }

  private void trim(int size) {
    while (history.size() > size) history.remove(0);
  }

  private static Operation withSignals(Operation op, Set<String> signals) {
    return new Operation(
        op.id,
        op.requestId,
        op.frozenCatalog,
        op.eventId,
        op.zoneId,
        op.state,
        op.node,
        op.cursor,
        op.created,
        op.expires,
        op.entered,
        op.budget,
        op.spent,
        op.outcome,
        signals);
  }

  private static Operation copy(
      Operation op, State state, String node, int cursor, long entered, int spent) {
    return new Operation(
        op.id,
        op.requestId,
        op.frozenCatalog,
        op.eventId,
        op.zoneId,
        state,
        node,
        cursor,
        op.created,
        op.expires,
        entered,
        op.budget,
        spent,
        "",
        op.latched);
  }
}
