package dev.deadearth.legacy.config;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.data.GameDataFiles;
import dev.deadearth.legacy.kernel.CinematicCatalog;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Human console transaction owner. All entry points run on the coordinator's server thread. */
public final class LiveConfiguration {
  public interface Adapter {
    /** Must do all validation/allocation before persistence; must not mutate live consumers. */
    Object prepare(Map<String, JsonObject> documents);

    /**
     * Only assignments of prepared snapshots; no I/O, registry calls, world effects or event hooks.
     */
    void commit(Object prepared);
  }

  private static final int MAX_RECEIPTS = 256;
  private final Path config, root, statePath;
  private final String epoch, campaign;
  private final Adapter adapter;
  private final JsonObject schema;
  private JsonObject state;
  private Map<String, JsonObject> active;
  private int failWrite;
  private boolean blocked;

  public LiveConfiguration(Path config, Path root, String epoch, String campaign, Adapter adapter)
      throws Exception {
    this.config = config.toAbsolutePath().normalize();
    this.root = root.toAbsolutePath().normalize();
    this.epoch = epoch;
    this.campaign = campaign;
    this.adapter = adapter;
    statePath = this.config.resolve("dead-earth/config-state.json");
    state = readState(statePath);
    if (state.has("transaction"))
      throw new IllegalStateException("Configuration recovery required");
    active = ConfigurationDocuments.read(this.config);
    try (InputStream in =
        LiveConfiguration.class.getResourceAsStream("/dead-earth-config-schema.json")) {
      if (in == null) throw new IOException("Missing native configuration schema");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] bytes = new byte[4096];
      int count;
      while ((count = in.read(bytes)) >= 0) {
        out.write(bytes, 0, count);
        if (out.size() > 262144) throw new IOException("Schema size");
      }
      schema =
          CinematicCatalog.strict(new String(out.toByteArray(), StandardCharsets.UTF_8), 262144)
              .getAsJsonObject();
    }
    // A successful process startup acknowledges saved configuration without replaying any action.
    boolean changed = false;
    for (Map.Entry<String, JsonElement> row : state.getAsJsonObject("receipts").entrySet()) {
      JsonObject result = row.getValue().getAsJsonObject().getAsJsonObject("result");
      if ("restart_pending".equals(result.get("state").getAsString())
          && epoch.equals(row.getValue().getAsJsonObject().get("worldEpoch").getAsString())
          && revision().equals(result.get("revision").getAsString())) {
        result.addProperty("state", "applied");
        changed = true;
      }
    }
    if (changed) write(statePath, state);
  }

  public boolean allowsExternalWrite() {
    return !blocked
        && !state.has("transaction")
        && !Files.exists(
            root.resolve("runtime/compose/config-restart-request.json"), LinkOption.NOFOLLOW_LINKS);
  }

  public void catalogChanged(JsonObject document) {
    active.put("catalog-rules", ConfigurationDocuments.copy(document));
  }

  public String revision() {
    return ConfigurationDocuments.revision(active);
  }

  public JsonObject view() {
    JsonObject out = new JsonObject();
    out.addProperty("schema", 1);
    out.addProperty("status", blocked ? "unknown" : "ready");
    out.addProperty("revision", revision());
    out.addProperty("worldEpoch", epoch);
    out.addProperty("campaignId", campaign);
    JsonArray sections = new JsonArray();
    for (JsonElement entry : schema.getAsJsonArray("sections")) {
      JsonObject section = ConfigurationDocuments.copy(entry.getAsJsonObject());
      String id = section.get("id").getAsString();
      JsonObject values = ConfigurationDocuments.fields(id, active.get(id));
      for (JsonElement element : section.getAsJsonArray("fields")) {
        JsonObject field = element.getAsJsonObject();
        field.add("value", ConfigurationDocuments.get(values, field.get("key").getAsString()));
      }
      sections.add(section);
    }
    out.add("sections", sections);
    return out;
  }

  public JsonObject receipt(String request) {
    if (!uuid(request)) return rejected(request, "invalid_request", "", "");
    JsonElement row = state.getAsJsonObject("receipts").get(request);
    return row == null
        ? rejected(request, "unknown_request", "", "")
        : ConfigurationDocuments.copy(row.getAsJsonObject().getAsJsonObject("result"));
  }

  public JsonObject command(
      String action, String requestedEpoch, String baseRevision, String request) {
    if (!uuid(request) || !baseRevision.matches("[0-9a-f]{64}"))
      return rejected(request, "invalid_request", "", "");
    if (!action.equals("validate") && !action.equals("apply"))
      return rejected(request, "invalid_arguments", "", "");
    try {
      Path stage = config.resolve("dead-earth/config-staging").resolve(request + ".json");
      safe(stage);
      JsonObject document = JsonFiles.read(stage, 1048576);
      GameDataFiles.onlyFields(
          document, "schema", "requestId", "worldEpoch", "baseRevision", "mode", "changes");
      GameDataFiles.integer(document, "schema", 1, 1);
      if (!text(document, "requestId").equals(request)
          || !text(document, "worldEpoch").equals(requestedEpoch)
          || !text(document, "baseRevision").equals(baseRevision))
        return rejected(request, "stage_mismatch", "", "");
      String fingerprint = JsonFiles.sha(ConfigurationDocuments.canonical(document).toString());
      JsonObject receipts = state.getAsJsonObject("receipts");
      if (receipts.has(request)) {
        JsonObject prior = receipts.getAsJsonObject(request);
        if (!prior.get("fingerprint").getAsString().equals(fingerprint)
            || !prior.get("worldEpoch").getAsString().equals(requestedEpoch))
          return rejected(request, "conflicting_request", "", "");
        if (!action.equals("apply")
            || !"validated".equals(prior.getAsJsonObject("result").get("state").getAsString()))
          return ConfigurationDocuments.copy(prior.getAsJsonObject("result"));
      }
      if (!epoch.equals(requestedEpoch)) return rejected(request, "stale_epoch", "", "");
      if (!revision().equals(baseRevision)) return rejected(request, "stale_revision", "", "");
      if (blocked || state.has("transaction"))
        return rejected(request, "recovery_required", "", "");
      if (Files.exists(
          root.resolve("runtime/compose/config-restart-request.json"), LinkOption.NOFOLLOW_LINKS))
        return rejected(request, "restart_pending", "", "");
      String mode = text(document, "mode");
      if (!mode.equals("hot") && !mode.equals("restart"))
        return rejected(request, "invalid_mode", "", "");
      if (!document.has("changes") || !document.get("changes").isJsonObject())
        return rejected(request, "invalid_changes", "", "");
      JsonObject changes = document.getAsJsonObject("changes");
      if (changes.entrySet().isEmpty() || changes.entrySet().size() > 13)
        return rejected(request, "invalid_changes", "", "");
      Map<String, JsonObject> next = ConfigurationDocuments.copy(active);
      JsonArray errors = new JsonArray();
      Set<String> edited = new LinkedHashSet<String>();
      int count = 0;
      for (Map.Entry<String, JsonElement> section : changes.entrySet()) {
        String id = section.getKey();
        if (!ConfigurationDocuments.IDS.contains(id) || !section.getValue().isJsonObject()) {
          error(errors, id, "", "unknown_section");
          continue;
        }
        if (section.getValue().getAsJsonObject().entrySet().isEmpty()) {
          error(errors, id, "", "empty_section");
          continue;
        }
        JsonObject values = ConfigurationDocuments.fields(id, next.get(id));
        for (Map.Entry<String, JsonElement> row : section.getValue().getAsJsonObject().entrySet()) {
          if (++count > 256) {
            error(errors, id, "", "too_many_fields");
            break;
          }
          String key = row.getKey();
          JsonObject field = field(id, key);
          if (field == null) {
            error(errors, id, key, "unknown_field");
            continue;
          }
          if (mode.equals("hot") && !field.get("policy").getAsString().equals("hot")) {
            error(errors, id, key, "restart_required");
            continue;
          }
          if (!valid(field, row.getValue())) {
            error(errors, id, key, "invalid_value");
            continue;
          }
          ConfigurationDocuments.set(values, key, row.getValue());
          edited.add(id);
        }
      }
      if (errors.size() > 0) return result(false, "rejected", request, revision(), errors);
      for (String id : edited) {
        try {
          JsonObject normalized = ConfigurationDocuments.normalize(id, next.get(id));
          if (normalized.toString().getBytes(StandardCharsets.UTF_8).length
              > ConfigurationDocuments.limit(id)) error(errors, id, "", "file_too_large");
          else next.put(id, normalized);
        } catch (RuntimeException invalid) {
          error(errors, id, "", "invalid_section");
        }
      }
      if (errors.size() > 0) return result(false, "rejected", request, revision(), errors);
      dev.deadearth.legacy.data.PhaseRoster candidateRoster =
          dev.deadearth.legacy.data.PhaseRoster.parse(next.get("srp-entities"));
      dev.deadearth.legacy.kernel.CinematicCatalog candidateCatalog =
          dev.deadearth.legacy.kernel.CinematicCatalog.parse(next.get("catalog-rules").toString());
      for (dev.deadearth.legacy.kernel.CinematicCatalog.Wave wave :
          candidateCatalog.waves().values())
        for (String entity : wave.entities())
          if (!candidateRoster.contains(entity))
            error(errors, "srp-entities", "entities", "catalogue_entity_required");
      JsonObject oldSieve = active.get("sieving"), newSieve = next.get("sieving");
      java.util.Set<String> oldNames = new java.util.HashSet<String>(),
          newNames = new java.util.HashSet<String>();
      for (java.util.Map.Entry<String, JsonElement> row :
          oldSieve.getAsJsonObject("materials").entrySet()) oldNames.add(row.getKey());
      for (java.util.Map.Entry<String, JsonElement> row :
          newSieve.getAsJsonObject("materials").entrySet()) newNames.add(row.getKey());
      if (!oldNames.equals(newNames))
        error(errors, "sieving", "materials", "registered_block_ids_required");
      oldNames.clear();
      newNames.clear();
      for (JsonElement row : oldSieve.getAsJsonArray("ownFirstCompression"))
        oldNames.add(row.getAsString());
      for (JsonElement row : newSieve.getAsJsonArray("ownFirstCompression"))
        newNames.add(row.getAsString());
      if (!oldNames.equals(newNames))
        error(errors, "sieving", "ownFirstCompression", "registered_block_ids_required");
      if (errors.size() > 0) return result(false, "rejected", request, revision(), errors);
      Object prepared;
      try {
        prepared = adapter.prepare(next);
      } catch (RuntimeException invalid) {
        return rejected(request, "invalid_native_configuration", "", "");
      }
      String desiredRevision = ConfigurationDocuments.revision(next);
      if (!receipts.has(request) && receipts.entrySet().size() >= MAX_RECEIPTS)
        return rejected(request, "journal_full", "", "");
      if (action.equals("validate")) {
        JsonObject validated = result(true, "validated", request, desiredRevision, errors);
        JsonObject claim = new JsonObject();
        claim.addProperty("fingerprint", fingerprint);
        claim.addProperty("worldEpoch", epoch);
        claim.add("result", validated);
        receipts.add(request, claim);
        try {
          write(statePath, state);
        } catch (IOException failedClaim) {
          receipts.remove(request);
          return rejected(request, "persistence_failed", "", "");
        }
        return validated;
      }
      // Detect out-of-band file changes; do not overwrite a different human edit.
      if (!ConfigurationDocuments.revision(ConfigurationDocuments.read(config))
          .equals(baseRevision)) return rejected(request, "disk_changed", "", "");
      return persist(request, fingerprint, mode, edited, next, desiredRevision, prepared);
    } catch (Exception invalid) {
      return rejected(request, "invalid_or_unreadable_stage", "", "");
    }
  }

  private JsonObject persist(
      String request,
      String fingerprint,
      String mode,
      Set<String> edited,
      Map<String, JsonObject> next,
      String desiredRevision,
      Object prepared) {
    String previous = revision();
    JsonObject before = ConfigurationDocuments.copy(state);
    JsonObject manifest = new JsonObject();
    manifest.addProperty("schema", 1);
    manifest.addProperty("requestId", request);
    manifest.addProperty("worldEpoch", epoch);
    manifest.addProperty("oldRevision", previous);
    manifest.addProperty("revision", desiredRevision);
    JsonArray files = new JsonArray();
    manifest.add("files", files);
    Path backup = root.resolve("runtime/compose/config-backups").resolve(request);
    boolean journalWritten = false, markerWritten = false;
    try {
      safe(backup);
      for (String id : edited) {
        Path source = ConfigurationDocuments.path(config, id);
        safe(source);
        byte[] bytes = Files.readAllBytes(source);
        Path target = backup.resolve(id + ".json");
        atomic(target, bytes);
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("sha256", JsonFiles.sha(new String(bytes, StandardCharsets.UTF_8)));
        files.add(row);
      }
      JsonObject transaction = ConfigurationDocuments.copy(manifest);
      transaction.addProperty("phase", "prepared");
      transaction.addProperty("fingerprint", fingerprint);
      state.add("transaction", transaction);
      write(statePath, state);
      journalWritten = true;
      int writeNumber = 0;
      for (String id : edited) {
        if (failWrite > 0 && ++writeNumber == failWrite)
          throw new IOException("Injected config write failure");
        write(ConfigurationDocuments.path(config, id), next.get(id));
      }
      JsonObject output =
          result(
              true,
              mode.equals("restart") ? "restart_pending" : "applied",
              request,
              desiredRevision,
              new JsonArray());
      JsonObject receipt = new JsonObject();
      receipt.addProperty("fingerprint", fingerprint);
      receipt.addProperty("worldEpoch", epoch);
      receipt.add("result", output);
      state.getAsJsonObject("receipts").add(request, receipt);
      transaction.addProperty("phase", "committed");
      write(statePath, state);
      if (mode.equals("restart")) {
        write(root.resolve("runtime/compose/config-restart-backup.json"), manifest);
        JsonObject marker = new JsonObject();
        marker.addProperty("schema", 1);
        marker.addProperty("requestId", request);
        marker.addProperty("worldEpoch", epoch);
        marker.addProperty("revision", desiredRevision);
        write(root.resolve("runtime/compose/config-restart-request.json"), marker);
        markerWritten = true;
      } else {
        adapter.commit(prepared);
        active = next;
      }
      state.remove("transaction");
      // The committed journal is already durable. Failure to compact it is recoverable at startup.
      try {
        write(statePath, state);
      } catch (Exception compactFailure) {
        blocked = true;
      }
      return output;
    } catch (Exception failure) {
      // The marker is the supervisor handoff. Before that handoff, disk and runtime both roll back.
      if (markerWritten) return receipt(request);
      try {
        if (journalWritten) restore(config, root, manifest);
        state = before;
        JsonObject failed = new JsonObject();
        failed.addProperty("fingerprint", fingerprint);
        failed.addProperty("worldEpoch", epoch);
        failed.add("result", rejected(request, "persistence_failed", "", ""));
        state.getAsJsonObject("receipts").add(request, failed);
        write(statePath, state);
      } catch (Exception rollbackFailure) {
        blocked = true;
        if (!journalWritten) state = before;
        else {
          state = before;
          JsonObject transaction = ConfigurationDocuments.copy(manifest);
          transaction.addProperty("phase", "prepared");
          transaction.addProperty("fingerprint", fingerprint);
          state.add("transaction", transaction);
          try {
            write(statePath, state);
          } catch (Exception ignored) {
          }
        }
      }
      return rejected(request, "persistence_failed", "", "");
    }
  }

  public static void recover(Path config, Path root) throws Exception {
    Path stateFile = config.toAbsolutePath().normalize().resolve("dead-earth/config-state.json");
    JsonObject state = readState(stateFile);
    if (!state.has("transaction")) return;
    JsonObject transaction = state.getAsJsonObject("transaction");
    if ("prepared".equals(text(transaction, "phase"))) {
      restore(config, root, transaction);
      String request = text(transaction, "requestId"),
          fingerprint = text(transaction, "fingerprint");
      if (!fingerprint.matches("[0-9a-f]{64}"))
        throw new IOException("Invalid recovery fingerprint");
      JsonArray errors = new JsonArray();
      error(errors, "", "", "recovery_reverted");
      JsonObject receipt = new JsonObject();
      receipt.addProperty("fingerprint", fingerprint);
      receipt.addProperty("worldEpoch", text(transaction, "worldEpoch"));
      receipt.add(
          "result", result(false, "rejected", request, text(transaction, "oldRevision"), errors));
      JsonObject receipts = state.getAsJsonObject("receipts");
      if (receipts.has(request) || receipts.entrySet().size() < MAX_RECEIPTS)
        receipts.add(request, receipt);
    } else if (!"committed".equals(text(transaction, "phase")))
      throw new IOException("Unknown configuration transaction");
    state.remove("transaction");
    write(stateFile, state);
  }

  private static void restore(Path config, Path root, JsonObject manifest) throws Exception {
    String request = text(manifest, "requestId");
    if (!uuid(request)) throw new IOException("Invalid recovery request");
    JsonArray files = manifest.getAsJsonArray("files");
    if (files == null || files.size() < 1 || files.size() > 13)
      throw new IOException("Recovery bounds");
    Set<String> ids = new HashSet<String>();
    Map<Path, byte[]> verified = new LinkedHashMap<Path, byte[]>();
    for (JsonElement element : files) {
      JsonObject row = element.getAsJsonObject();
      String id = text(row, "id");
      if (!ConfigurationDocuments.IDS.contains(id) || !ids.add(id))
        throw new IOException("Recovery whitelist");
      Path source =
          root.resolve("runtime/compose/config-backups").resolve(request).resolve(id + ".json");
      safe(source);
      if (Files.size(source) > ConfigurationDocuments.limit(id))
        throw new IOException("Recovery size");
      byte[] bytes = Files.readAllBytes(source);
      if (!JsonFiles.sha(new String(bytes, StandardCharsets.UTF_8)).equals(text(row, "sha256")))
        throw new IOException("Recovery checksum");
      verified.put(ConfigurationDocuments.path(config, id), bytes);
    }
    for (Map.Entry<Path, byte[]> row : verified.entrySet()) atomic(row.getKey(), row.getValue());
  }

  private static JsonObject readState(Path path) throws Exception {
    safe(path);
    if (!Files.exists(path)) {
      JsonObject state = new JsonObject();
      state.addProperty("schema", 1);
      state.add("receipts", new JsonObject());
      return state;
    }
    JsonObject state = JsonFiles.read(path, 2097152);
    GameDataFiles.onlyFields(state, "schema", "receipts", "transaction");
    GameDataFiles.integer(state, "schema", 1, 1);
    JsonObject receipts = state.getAsJsonObject("receipts");
    if (receipts == null || receipts.entrySet().size() > MAX_RECEIPTS)
      throw new IOException("Configuration receipt bounds");
    for (Map.Entry<String, JsonElement> row : receipts.entrySet())
      if (!uuid(row.getKey())
          || !row.getValue().isJsonObject()
          || !text(row.getValue().getAsJsonObject(), "fingerprint").matches("[0-9a-f]{64}"))
        throw new IOException("Invalid configuration receipt");
    return state;
  }

  private JsonObject field(String id, String key) {
    for (JsonElement element : schema.getAsJsonArray("sections")) {
      JsonObject section = element.getAsJsonObject();
      if (!section.get("id").getAsString().equals(id)) continue;
      for (JsonElement row : section.getAsJsonArray("fields")) {
        JsonObject field = row.getAsJsonObject();
        if (field.get("key").getAsString().equals(key)) return field;
      }
    }
    return null;
  }

  private static boolean valid(JsonObject field, JsonElement value) {
    if (value == null || value.isJsonNull() || value.toString().length() > 524288) return false;
    String type = field.get("type").getAsString();
    if (type.equals("json")) return value.isJsonArray() || value.isJsonObject();
    if (!value.isJsonPrimitive()) return false;
    JsonPrimitive p = value.getAsJsonPrimitive();
    if (type.equals("boolean")) return p.isBoolean();
    if (type.equals("string")) {
      if (!p.isString() || p.getAsString().length() > 160) return false;
      if (field.has("choices")) {
        boolean found = false;
        for (JsonElement choice : field.getAsJsonArray("choices"))
          if (choice.equals(value)) found = true;
        if (!found) return false;
      }
      return true;
    }
    if (!p.isNumber() || !Double.isFinite(p.getAsDouble())) return false;
    if (type.equals("integer")) {
      if (!p.getAsString().matches("-?(0|[1-9][0-9]*)")) return false;
      try {
        Long.parseLong(p.getAsString());
      } catch (NumberFormatException overflow) {
        return false;
      }
    }
    double number = p.getAsDouble();
    return (!field.has("min") || number >= field.get("min").getAsDouble())
        && (!field.has("max") || number <= field.get("max").getAsDouble());
  }

  private JsonObject rejected(String request, String code, String section, String key) {
    JsonArray errors = new JsonArray();
    error(errors, section, key, code);
    return result(false, "rejected", request, revision(), errors);
  }

  private static void error(JsonArray errors, String section, String key, String code) {
    if (errors.size() >= 32) return;
    JsonObject out = new JsonObject();
    out.addProperty("section", safeText(section));
    out.addProperty("key", safeText(key));
    out.addProperty("code", code);
    out.addProperty("message", code.replace('_', ' '));
    errors.add(out);
  }

  private static String safeText(String text) {
    return text != null && text.matches("[a-zA-Z0-9_.-]{0,96}") ? text : "";
  }

  private static JsonObject result(
      boolean accepted, String status, String request, String revision, JsonArray errors) {
    JsonObject out = new JsonObject();
    out.addProperty("schema", 1);
    out.addProperty("accepted", accepted);
    out.addProperty("state", status);
    out.addProperty("requestId", uuid(request) ? request : "");
    out.addProperty("revision", revision);
    out.add("errors", errors);
    return out;
  }

  private static boolean uuid(String text) {
    try {
      return text != null && UUID.fromString(text).toString().equals(text);
    } catch (IllegalArgumentException bad) {
      return false;
    }
  }

  private static String text(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected text");
    return value.getAsString();
  }

  public static void safe(Path path) throws IOException {
    for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent())
      if (Files.isSymbolicLink(p)) throw new IOException("Symbolic link in configuration path");
  }

  private static void write(Path path, Object object) throws IOException {
    atomic(path, JsonFiles.JSON.toJson(object).getBytes(StandardCharsets.UTF_8));
  }

  private static void atomic(Path path, byte[] bytes) throws IOException {
    path = path.toAbsolutePath().normalize();
    safe(path);
    Files.createDirectories(path.getParent());
    safe(path.getParent());
    Path temporary = Files.createTempFile(path.getParent(), "config-", ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      try {
        Files.move(
            temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Package-private deterministic fault injection; never a command or model capability. */
  void failWriteForTest(int writeNumber) {
    failWrite = writeNumber;
  }
}
