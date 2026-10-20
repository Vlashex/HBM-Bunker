package dev.deadearth.legacy;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;

public final class JsonFiles {
  public static final Gson JSON = new GsonBuilder().serializeNulls().create();

  public static JsonObject read(Path p, int limit) throws IOException {
    if (Files.isSymbolicLink(p) || Files.size(p) > limit)
      throw new IOException("Unsafe/oversize JSON");
    return dev.deadearth.legacy.kernel.CinematicCatalog.strict(
            new String(Files.readAllBytes(p), StandardCharsets.UTF_8), limit, true)
        .getAsJsonObject();
  }

  public static void write(Path p, Object value) {
    try {
      Files.createDirectories(p.toAbsolutePath().getParent());
      Path t = Files.createTempFile(p.toAbsolutePath().getParent(), "dead-earth-", ".tmp");
      try {
        Files.write(t, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        try {
          Files.move(t, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(t, p, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(t);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not checkpoint " + p.getFileName(), e);
    }
  }

  public static String sha(String s) {
    try {
      byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte v : b) out.append(String.format("%02x", v & 255));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static java.util.Map<String, Object> map(Object... pairs) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }
}
