package dev.deadearth.campaign;

import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;

public final class SavedDataAccess {
    private SavedDataAccess() {}

    public static void checkpoint(ServerLevel overworld, String name, SavedData data) {
        if (!overworld.getServer().isSameThread()) throw new IllegalStateException("Checkpoint outside server thread");
        validateName(name);
        if (!data.isDirty()) return;
        // Vanilla SavedData writes asynchronously and logs I/O errors. A scenario needs an acknowledged barrier.
        IOUtilities.waitUntilIOWorkerComplete();
        var root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), overworld.registryAccess()));
        NbtUtils.addCurrentDataVersion(root);
        var file = overworld.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
        try {
            Files.createDirectories(file.getParent());
            IOUtilities.writeNbtCompressed(root, file);
            data.setDirty(false);
        } catch (IOException error) {
            throw new UncheckedIOException("Dead Earth checkpoint failed", error);
        }
    }

    /** A failed read must never silently replace an existing campaign. Overworld only. */
    public static <T extends SavedData> T open(ServerLevel overworld, String name, SavedData.Factory<T> factory) {
        if (!overworld.getServer().isSameThread()) throw new IllegalStateException("SavedData access outside server thread");
        validateName(name);
        var storage = overworld.getDataStorage();
        T existing = storage.get(factory, name);
        if (existing != null) return existing;
        var file = overworld.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
        if (Files.exists(file)) throw new IllegalStateException("Cannot read existing Dead Earth save: " + name);
        T created = factory.constructor().get();
        storage.set(name, created);
        return created;
    }

    private static void validateName(String name) {
        if (!name.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid Dead Earth data name");
    }
}
