package dev.deadearth.legacy.data;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Shared strict configuration decoding. Missing optional keys retain Java defaults. */
public final class GameDataFiles {
  private GameDataFiles() {}

  public static <T> T load(Path path, Class<T> type, int limit) throws IOException {
    return decode(JsonFiles.read(path, limit), type, path.getFileName().toString());
  }

  public static <T> T decode(JsonObject document, Class<T> type, String source) {
    validateObject(document, type, source);
    T value = JsonFiles.JSON.fromJson(document, type);
    if (value == null) throw new IllegalArgumentException("Missing configuration " + source);
    return value;
  }

  public static void onlyFields(JsonObject object, String... allowed) {
    Set<String> fields = new HashSet<String>(Arrays.asList(allowed));
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      if (!fields.contains(entry.getKey()) || entry.getValue().isJsonNull()) {
        throw new IllegalArgumentException("Unknown or null data field " + entry.getKey());
      }
    }
  }

  public static int integer(JsonObject object, String key, int minimum, int maximum) {
    JsonElement value = object.get(key);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
      throw new IllegalArgumentException("Expected integer " + key);
    }
    long number;
    try {
      number = Long.parseLong(value.getAsString());
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Integer overflow " + key, invalid);
    }
    if (number < minimum || number > maximum)
      throw new IllegalArgumentException("Out of range " + key);
    return (int) number;
  }

  private static void validateObject(JsonObject object, Class<?> type, String at) {
    Map<String, Field> fields = new HashMap<String, Field>();
    for (Field field : type.getFields()) {
      if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
        fields.put(field.getName(), field);
      }
    }
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      Field field = fields.get(entry.getKey());
      if (field == null)
        throw new IllegalArgumentException("Unknown setting " + at + "." + entry.getKey());
      validate(entry.getValue(), field.getGenericType(), at + "." + entry.getKey());
    }
  }

  private static void validate(JsonElement value, Type type, String at) {
    if (value == null || value.isJsonNull())
      throw new IllegalArgumentException("Null setting " + at);
    if (type instanceof ParameterizedType) {
      ParameterizedType generic = (ParameterizedType) type;
      Type raw = generic.getRawType();
      if (raw instanceof Class<?> && Map.class.isAssignableFrom((Class<?>) raw)) {
        if (!value.isJsonObject() || generic.getActualTypeArguments()[0] != String.class)
          invalid(at);
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
          validate(
              entry.getValue(), generic.getActualTypeArguments()[1], at + "." + entry.getKey());
        }
        return;
      }
      if (raw instanceof Class<?> && Collection.class.isAssignableFrom((Class<?>) raw)) {
        if (!value.isJsonArray() || generic.getActualTypeArguments().length != 1) invalid(at);
        for (JsonElement entry : value.getAsJsonArray())
          validate(entry, generic.getActualTypeArguments()[0], at + "[]");
        return;
      }
      throw new IllegalArgumentException("Unsupported configuration type " + at);
    }
    if (!(type instanceof Class<?>))
      throw new IllegalArgumentException("Unsupported configuration type " + at);
    Class<?> target = (Class<?>) type;
    if (target.isArray()) {
      if (!value.isJsonArray()) invalid(at);
      for (JsonElement element : value.getAsJsonArray())
        validate(element, target.getComponentType(), at + "[]");
    } else if (target == boolean.class || target == Boolean.class) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) invalid(at);
    } else if (target == String.class) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) invalid(at);
    } else if (target == int.class
        || target == Integer.class
        || target == long.class
        || target == Long.class) {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isNumber()
          || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) invalid(at);
      try {
        long number = Long.parseLong(value.getAsString());
        if ((target == int.class || target == Integer.class)
            && (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)) invalid(at);
      } catch (NumberFormatException overflow) {
        throw new IllegalArgumentException("Integer overflow " + at, overflow);
      }
    } else if (target == double.class
        || target == Double.class
        || target == float.class
        || target == Float.class) {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isNumber()
          || !Double.isFinite(value.getAsDouble())
          || ((target == float.class || target == Float.class)
              && !Float.isFinite(value.getAsFloat()))) invalid(at);
    } else {
      if (!value.isJsonObject()) invalid(at);
      validateObject(value.getAsJsonObject(), target, at);
    }
  }

  private static void invalid(String at) {
    throw new IllegalArgumentException("Wrong configuration type " + at);
  }
}
